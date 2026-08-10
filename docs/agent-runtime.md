# SeekFlux Agent Runtime 内核设计

本文记录已经实现的 Agent Runtime 细节。系统全局目标见 [`SeekFlux.md`](../SeekFlux.md)，阶段状态见[学习路线](learning/README.md)，长期决策见 [ADR-004](adr/ADR-004-ark-leto-inspired-agent-runtime.md)。

## 1. 实现口径

SeekFlux 没有依赖 Ark-Leto 二进制或源码。当前实现参考《Ark-Leto 框架内核 与 Agentspark 主链路 原理详解》中的主链路、会话事件和执行权思想，自行实现内部 Runtime。类名相似只代表设计映射，不代表复制或集成了未提供的框架。

Phase 1 已证明业务无关、有界、可追踪、能稳定回退的运行内核；Phase 2 完成 Query Mode、多轮约束、动态并行 Tool、OpenAI-compatible Provider Adapter 和复杂 Query Eval；Phase 3 已补齐 fencing、失主接管、跨实例取消、事务 Outbox、故障注入、Shadow 与成本计量边界。

## 2. 模块职责

```mermaid
flowchart LR
    API["agent-server / HTTP"] --> AO["AgentOrchestration Context"]
    AO --> ADAPTER["AgentExecutionPort Adapter"]
    ADAPTER --> ROUTER["Router"]
    ROUTER --> PIPE["FeaturePipeline"]
    ROUTER --> EXEC["SessionExecutor"]
    EXEC --> LOOP["AgentLoop"]
    LOOP --> CTX["ContextEngine"]
    LOOP --> LLM["LlmClient SPI"]
    LOOP --> TOOLS["Tool Registry / Executor"]
    TOOLS --> SEARCH["Search Tools → SearchUseCase"]
    ROUTER --> SESSION["WorkspaceEvent Store"]
    LOOP --> RUNS["AgentRun / RunEvent Recorder"]
    EXEC --> AUTH["ExecutionAuthority Store"]
```

| 模块 | 已实现职责 | 禁止拥有的职责 |
| --- | --- | --- |
| `platform/agent-runtime` | Router、Feature、Session 执行、有限步 Loop、上下文、Tool、运行事件 | Search 约束语义、Spring、模型厂商 SDK、Redis/ES 访问 |
| `contexts/agent-orchestration-context` | SearchGoal/ConstraintPatch、SearchPlan、Query Mode、追问与回退业务状态、输入/输出 Port | 线程池、租约实现、HTTP、直接检索索引 |
| `apps/agent-server` | Spring 装配、HTTP、Redis/JDBC/Search Tool/决策 Provider Adapter | 在 Controller 内规划或过滤结果 |
| `platform/persistence` | Session 追加事件、最新投影、Run/RunEvent 持久化 | Agent 业务决策 |

## 3. 主链路

```mermaid
sequenceDiagram
    autonumber
    participant Client
    participant Router
    participant Feature as FeaturePipeline
    participant Authority as ExecutionAuthority
    participant Store as SessionStore
    participant Executor as SessionExecutor
    participant Loop as AgentLoop
    participant LLM as LlmClient
    participant Tool as Search Tools
    participant Search as SearchUseCase

    Client->>Router: AgentRunRequest
    Router->>Feature: process
    Feature-->>Router: frozen RuntimeContext
    Router->>Authority: acquire(sessionId)
    alt session 正在执行
        Authority-->>Router: busy
        Router-->>Client: 409 AGENT_SESSION_BUSY
    else 获得执行权
        Router->>Store: commitIngress(requestId, UserMessage)
        alt requestId 已完成或正在由有效 owner 处理
            Store-->>Router: duplicate
            Router-->>Client: 409 DUPLICATE_AGENT_REQUEST
        else 崩溃中的同 requestId 被更高 fencing token 接管
            Store-->>Router: recovered
            Router->>Executor: 强一致恢复并创建新 attempt
        else 新请求
            Router->>Executor: run
            Executor->>Store: restoreFresh
            Executor->>Loop: run
            Loop->>LLM: chat(assembled context)
            LLM-->>Loop: CallTool / CallTools
            Loop->>Tool: validated bounded invocation(s)
            Tool->>Search: SearchUseCase.search
            Search-->>Tool: SearchResult + SearchTrace
            Loop->>LLM: chat(context + observation)
            LLM-->>Loop: Complete
            Executor->>Authority: final renew / fence check
            Executor->>Store: appendOutcome + Outbox in one transaction
            Executor->>Authority: release owner
            Loop-->>Client: RESULTS_READY + AgentTrace + SearchTrace
        end
    end
```

最重要的位置约束是“先取得执行权，再提交用户消息”。否则两个实例可能先后提交两条都声称可执行的消息，之后再争抢锁已经无法消除双写。Redis 原子分配单调 fencing token，PostgreSQL 只接受当前 token 的终态；释放时先移除本机 CancellationToken，再按 owner 比较释放租约，避免误删新 owner 的执行权。

## 4. FeaturePipeline

FeatureNode 不依赖 Spring 扫描顺序，装配层明确传入列表，Pipeline 再按 `order()` 排序：

| 顺序 | 节点 | 输出 |
| ---: | --- | --- |
| 100 | SessionLoad | 现有 Session 投影 |
| 200 | AgentResolve | AgentDef、LlmClient 和冻结版本上下文 |
| 300 | ParamInit | 规范化运行参数 |
| 400 | ResumeEval | 当前是否具备继续执行条件 |

持久数据与请求瞬态数据分别放在 `FeatureContext` 和 `RuntimeContext`，避免把线程、连接或临时 Publisher 序列化进 Session。

## 5. Session、Run 与 PushEvent

三类事件职责独立：

| 类型 | 存储/生命周期 | 用途 |
| --- | --- | --- |
| `WorkspaceEvent` | PostgreSQL 追加式事实源 | 重建 Session：Created、StatePatched、UserMessage、RunCompleted/Cancelled/Failed |
| `AgentRunEvent` | PostgreSQL 独立运行表 | 诊断每次 Decision、Tool 和终态，关联版本及 Search Trace ID |
| `PushEvent` | 请求内 Publisher；当前不持久化 | 客户端过程投影；Phase 1 同步响应内按逻辑顺序缓冲 |

PostgreSQL 的 `agent.sessions` 保存最新版本、状态版本、事件位置、快照和当前 fencing token，`agent.workspace_events` 以 `(session_id, event_position)` 排序，并以 `(session_id, request_id)` 保证 Ingress 幂等。状态补丁和 UserMessage 在同一事务中提交，旧 `baseVersion` 不能覆盖新目标。终态 WorkspaceEvent 与 `outbox.events` 也在同一事务提交，Worker 按确定性 `eventId` 幂等写入 `agent.audit_events`。`agent.runs` 与 `agent.run_events` 记录每个失主/接管 attempt，但不参与 Workspace 重放。Redis 只保存热投影、执行权、取消信号和 Shadow 开关，不是 Session 真相源。

## 6. 有限步 AgentLoop

`AgentRuntime` 对每次运行建立共同 Deadline，并冻结以下版本：Agent、Planner、Prompt、Decision Provider、请求级 Tool Schema 子集。每一步接受以下结构化 Decision：

- `CallTool`：先校验 Tool 是否允许、参数 Schema、Tool 次数和 Deadline，再由 ToolExecutor 执行；
- `CallTools`：在同一总预算下把多个调用提交给有界执行器；部分成功继续，全部失败回退；
- `Complete`：结束为 `RESULTS_READY`；
- `Clarify`：结束为 `NEED_CLARIFICATION`；
- `Fallback`：结束为 `FALLBACK_REQUIRED`，由业务 Adapter 决定确定性回退。

Tool 参数校验失败后只做一次不改变业务意图的确定性修复；相同规范化 Tool 指纹再次出现时返回 `NO_PROGRESS_DETECTED`。执行器是命名、有界线程池；超时会取消 Future，队列饱和产生稳定失败原因。Runtime 不使用公共线程池，也不把异步类型暴露到 Port 或 HTTP。

模型和 Tool 还受两个独立 Bulkhead 保护，分别返回 `MODEL_BULKHEAD_FULL` 和 `TOOL_BULKHEAD_FULL`；故障注入只存在于 Runtime 调用边界，不要求业务 Tool 编写测试分支。Tool Call ID 由 request/step/tool/规范化参数确定性生成，Tool 同时声明副作用类型。当前两个 Search Tool 都是只读；尚未为写 Tool 实现持久化副作用账本。

## 7. Search Agent

当前提供两个 AgentDef：

| Agent | 版本 | 最大步数 | Tool |
| --- | --- | ---: | --- |
| `search-assistant` | `search-assistant-v2` | 4 | `search_direct@v1`、`search_filtered@v1` |
| `search-precise` | `search-precise-v2` | 3 | `search_direct@v1`、`search_filtered@v1` |

二者复用同一 Runtime。默认 `DeterministicSearchLlmClient@deterministic-complex-search-decision-v2` 无需 API Key，可以稳定验证“并行 Search Tool → 观察结果 → 选择候选集 → 完成”。`OpenAiCompatibleLlmClient` 已实现真实 Chat Completions 兼容协议、结构化 Decision、usage 解析和配置价格换算；协议测试不等同于真实模型质量或付费成本评测，确定性 Provider 的 Trace 会明确 `usageMeasured=false`。

`ShadowingLlmClient` 在独立有界线程池运行候选策略，只同步返回 primary。候选结果、延迟、错误和一致性写入 `agent.shadow_evaluations`；Redis 共享的开关/采样率使任一实例关闭后其他实例下一次请求生效。Shadow 拒绝或失败不会影响主链。

`SearchDirectTool` 和 `SearchFilteredTool` 都只调用 `SearchUseCase`。复杂 Query 同时执行原 Query 宽搜与改写 Query + 派生标签精搜，最终原样复用一个 Search Tool 候选集；Agent 不二次重排。Tool 成功后，Agent Step 中的 `linkedTraceId` 指向权威 Search Trace；模型/全部 Tool/Runtime 无法完成时，Adapter 使用原 SearchGoal 调用同一个 Search Use Case，响应模式为 `AGENT_TO_DIRECT_FALLBACK`。

`AUTO` Query Mode 在进入 Runtime 前分流：简单 Query 直接调用 Search Use Case，返回 `executionMode=DIRECT` 且不创建 AgentRun；复杂 Query 或 ConstraintPatch 才进入 Agent。响应返回 `routeReason`、`SearchPlan`、`goalVersion`、所选 Tool 和候选复用证据。

## 8. API 与运行

Agent Server 默认端口为 `8083`，避免与可选 Flink UI 的 `8082` 冲突：

```bash
./seekflux.sh up
./seekflux.sh status
./seekflux.sh logs agent
```

同步 API：

```http
POST /v1/agent/search
POST /v1/agent/sessions/{sessionId}:cancel
GET  /v1/agent/runtime/shadow
PUT  /v1/agent/runtime/shadow
```

搜索响应同时返回稳定业务状态、`AgentTrace` 和可选的 `SearchTrace`。完整请求/响应 Schema 见 [`contracts/openapi/seekflux-v1.yaml`](../contracts/openapi/seekflux-v1.yaml)。

## 9. 验证和当前边界

```bash
mvn -pl platform/agent-runtime,apps/agent-server,apps/worker-runner -am test
python3 evals/run_agent_search_eval.py
python3 evals/run_complex_agent_eval.py
python3 evals/run_agent_reliability_eval.py
```

固定 `direct-search-v1` 六 Query 基线上，强制 Agent 与 Direct 的 `Recall@5/MRR@5/nDCG@5` 均为 `1.0`，证明基础复用没有回归。`complex-search-v1` 的六条关键词陷阱 Query 中，Direct `MRR@1/Recall@1=0.0`，Agent `MRR@1/Recall@1=1.0`；Tool 选择、任务完成、简单 Direct 路由和多轮版本测试全部通过。

`agent-reliability-v1` 固定评测证明单写者、fencing 单调、重复请求无额外 Tool 事件、事务 Outbox、幂等审计、Shadow 主结果隔离和快速关闭；12 次样本可用性 `1.0`、P95 `226.402 ms`、Fallback `0.0`。旧 owner、跨实例取消、模型/Tool 故障和 Bulkhead 另有自动化测试。

对照 Ark-Leto 后仍未完成的是 steer 排队、pending Tool Checkpoint、写 Tool 副作用账本、上下文压缩、OutputGuard、实时 Push/SSE、HITL、Handoff、子 Agent、MCP/Skill/Graph 和完整 OTel。真实付费 Provider 基线也需要部署方端点和密钥；当前报告不伪造 Token/成本。完整取舍见 [ADR-006](adr/ADR-006-agent-reliability-fencing-outbox-shadow.md)。
