# Step 7：Agent 可靠性与平台化

## 本阶段状态

- 状态：已完成
- 完成日期：2026-08-10
- 对应开发 Step：Step 7
- 对应 Agent Phase：Phase 3
- 对应决策：[ADR-006：Agent 多实例可靠性、事务事实与 Shadow 治理](../adr/ADR-006-agent-reliability-fencing-outbox-shadow.md)
- 对应契约：[`contracts/openapi/seekflux-v1.yaml`](../../contracts/openapi/seekflux-v1.yaml)、[`agent-workspace-message-v1.schema.json`](../../contracts/events/agent-workspace-message-v1.schema.json)、[`agent-recovery-v1.schema.json`](../../contracts/events/agent-recovery-v1.schema.json)
- 固定评测：[`evals/results/agent-reliability-v1-baseline.json`](../../evals/results/agent-reliability-v1-baseline.json)

## 要解决的问题

Phase 2 证明了 Agent 的编排增量，但租约过期、实例退出、重复请求、跨实例取消、模型/Tool 饱和和策略升级都可能破坏线上正确性。本阶段把“单实例能运行”提升为“旧 owner 不能晚到提交、终态事实可以可靠传播、故障可稳定回退、Shadow 不污染主结果”。

## 完成了什么

- Redis 原子获取执行权、单调 fencing token、owner-CAS 续租/释放；
- PostgreSQL `active_fencing_token` 对 Ingress、状态补丁和终态提交的全区间保护；
- 失主接管时从 WorkspaceEvent 强一致恢复，相同请求的崩溃中轮次可生成新 attempt；
- 原因化 Redis 跨实例取消、旧信号过滤、Session → batch → Tool 分层传播和有宽限期的优雅停机；
- 真实 `DefaultAgentLoop` 在模型/Tool 调用期间响应取消；`USER_CANCEL`、`STEER`、`AUTHORITY_LOST`、`SHUTDOWN` 统一进入 `CANCELLED`，Run、Trace、Push、HTTP 和 PostgreSQL 记录同一 `cancellationReason`，不会误触发 Direct Fallback；
- User/Assistant/ToolResult/终态 WorkspaceEvent 与确定性 Agent Outbox 同一 fencing 事务提交；完整多轮历史可只从 PostgreSQL 事实重建；
- Assistant 正文/reasoning/tool calls 分离，ToolResult 保存 raw/model/display/structured/resources 多视图并用稳定 call ID 强制一一配对；Clarification 投影为 `SUSPENDED`；
- 版本化 Runtime Checkpoint 在 PRE_TURN、POST_TURN、COMPLETED/SUSPENDED 保存可恢复运行态；pending Tool journal 区分 DECIDED、EXECUTING、UNKNOWN 与结果终态；
- 接管统一执行 renew → restoreFresh → cutoff 校验 → commitResume → dispatch：安全 Tool 复用结果或按稳定 call ID 重试，未知 `MUTATING` Tool 在 Loop 前失败关闭；
- 四类 Agent 终态 Topic 与按 `eventId` 幂等的审计消费者；
- 模型/Tool 独立 Bulkhead、稳定错误、确定性 Tool Call ID 和效果类型；
- 模型、Tool、失主、跨实例取消及 Bulkhead 故障测试；
- OpenAI-compatible usage 解析、价格换算、Trace 字段和版本化 Micrometer 指标；
- 隔离执行器中的 Shadow、PostgreSQL 对比记录、Redis 跨实例开关和管理 API；
- OpenAI-compatible 响应兼容标准 `message.content` 与 LongCat `message.reasoning_content`，两种结构都保留真实 usage；
- Agent Runtime 内核完成 DDD 物理分层：Application 只保留 `api`、`command`、`spi/business`、`spi/capability` 对外契约；领域模型按 Agent Definition、Decision、Run、Session、Tool、Feature、Execution 细分；Runtime、Router、Loop、Feature、Context、Tool、Execution、Shadow 编排进入 `domain/service`，默认 SPI 实现进入 `infrastructure`；外层 Agent Server 的 HTTP API 对应系统 `interfaces/rest`；
- Runtime 技术实现和 Search Agent 业务适配器按所有者内聚：执行权、取消和 Shadow 配置的 Redis 默认实现进入 `platform/agent-runtime/infrastructure`，Runtime/Context 映射、Search Tool、Direct Fallback、投影、确定性决策、OpenAI-compatible Provider 和指标进入 `contexts/agent-orchestration-context/infrastructure`；Agent Server 只保留 REST、启动和组合装配；
- C 端新增任务型 AI 搜索界面，通过同源 Bridge 直连 Agent Server，支持多轮 Goal 版本、追问、取消、降级提示和真实 Search 候选展示；
- macOS 中间件改由 launchd 托管，解决启动命令结束后 Kafka/ES/MinIO 退出的问题；
- 自带样本发布、索引等待、清理和数据库断言的可靠性 Eval。

## 核心流程与失败语义

1. Router 先取得 `owner|fencingToken`，再提交 Ingress；同 Session 已被持有返回 409；
2. 新请求原子写状态补丁/UserMessage并将 token 记录为当前 owner；普通重复请求返回 `DUPLICATE_AGENT_REQUEST`；
3. 如果相同请求已经提交而 Session 仍在 `EXECUTING`，更高 token 认领该轮，从 PostgreSQL 事件和 Checkpoint/journal 恢复；已完成 Tool 不重放，安全 pending Tool 可继续，旧 RUNNING attempt 标记 `OWNER_LOST`；
4. 执行期间定时续租；Redis 不可用或 owner 改变即取消本地 Loop，提交前再次续租失败则返回 fenced，不写 Session 终态/Outbox；
5. 本地或其他实例写入的取消信号由运行中的 token 一次读取时间与原因，只有晚于任务开始的信号有效；首个原因胜出并向 batch/Tool 子 token 传播，在途模型/Tool Future 被中断，晚到结果被丢弃；
6. 正常、回退、取消或失败终态在一个事务中写 WorkspaceEvent 和 Outbox，Kafka 消费者可重复处理但数据库审计只保留一条；
7. 模型/Tool 故障或 Bulkhead 饱和进入稳定回退/部分成功语义；Shadow 永远异步旁路，关闭通过 Redis 对所有实例生效。

## 关键代码入口

| 入口 | 作用 |
| --- | --- |
| `platform/agent-runtime/.../application/api/Router.java` | 业务调用 Runtime 的公开 API |
| `platform/agent-runtime/.../application/command/` | AgentRunRequest、FeatureRequest 等输入命令 |
| `platform/agent-runtime/.../application/spi/business/` | Planner、Tool、Feature、Context 等业务定制 SPI |
| `platform/agent-runtime/.../application/spi/capability/` | Session、LLM、执行权、记录和事件等能力 SPI |
| `platform/agent-runtime/.../domain/service/execution/SessionExecutor.java` | fencing、续租、恢复、跨实例取消、优雅停机 |
| `platform/agent-runtime/.../infrastructure/redis/RedisExecutionAuthorityStore.java` | 原子 fencing 计数与 owner-CAS Lua |
| `platform/persistence/.../JdbcAgentSessionStore.java` | 受 fencing 保护的消息/Outcome/Outbox 事务与 Workspace 重放 |
| `platform/persistence/.../WorkspaceMessageCodec.java` | 版本化 Assistant/ToolResult payload 的 JSON 边界映射 |
| `platform/agent-runtime/.../domain/model/recovery/` | Checkpoint、Tool journal、ResumeIngress/ResumeAction 领域契约 |
| `platform/agent-runtime/.../domain/service/recovery/AgentRecoveryExecution.java` | 安全点写入和固定崩溃注入边界 |
| `platform/persistence/.../JdbcAgentRecoveryStore.java` | fencing 下的 Checkpoint/journal 与原子 Resume 入口 |
| `platform/agent-runtime/.../domain/service/execution/AgentCallGuard.java` | 模型/Tool Bulkhead 与故障注入边界 |
| `platform/agent-runtime/.../infrastructure/llm/ShadowingLlmClient.java` | 不影响主链的 Shadow 执行 |
| `platform/agent-runtime/.../infrastructure/redis/RedisShadowSettingsStore.java` | 跨实例 Shadow 开关 |
| `contexts/agent-orchestration-context/.../infrastructure/runtime/AgentRuntimeExecutionAdapter.java` | Context 输出 Port 与 Runtime API 的业务映射 |
| `apps/worker-runner/.../AgentOutcomeAuditWorker.java` | 幂等 Agent 终态审计消费者 |
| `evals/run_agent_reliability_eval.py` | 真实链路可靠性/SLO 固定评测 |

## 完成证据

- 2026-09-12 DDD 包结构调整后，Agent Runtime 23 个测试通过；包含 Agent Orchestration、Agent Server、Worker 及依赖在内的 17 个 Reactor 模块完整测试无失败；
- 2026-09-13 Adapter 按所有者收敛后，Agent Runtime 26 个测试、Agent Orchestration Context 7 个测试通过；包含 Server、Worker 及依赖在内的 17 个 Reactor 模块 clean 回归无失败；
- 2026-09-13 模型 Provider 所有权修正后，OpenAI-compatible Adapter 及其 3 个协议测试迁入 Agent Orchestration Context；Agent Runtime 23 个测试、Agent Orchestration Context 10 个测试通过，17 个 Reactor 模块共 76 个测试 clean 回归无失败；
- 2026-09-13 AR-1 取消闭环后，JDK 21 下 Agent Runtime 31 个测试通过；Agent Orchestration Context 及依赖、Persistence + Agent Server 及依赖两组回归通过。固定测试覆盖模型前取消、模型中断、Tool 中断、取消后禁止下一模型轮、跨实例 `USER_CANCEL`、停机 `SHUTDOWN`、失权禁止提交、OpenAI 调用中断及取消响应不触发 Direct Fallback；
- 2026-09-13 AR-2 消息历史闭环后，JDK 21 下 Agent Runtime 36 个、Persistence 3 个、Agent Orchestration Context 12 个测试无失败，Agent Server 编译通过；隔离 PostgreSQL 17 顺序执行 V1～V9 成功。固定测试覆盖跨 JSON 进程边界的第二轮历史恢复、稳定消息/Tool Call ID、并行顺序、取消 ToolResult、旧 UserMessage、未知字段兼容以及孤立/缺失结果拒绝；
- 2026-09-14 AR-3 精确恢复后，JDK 21 下 Agent Runtime 48 个、Persistence 5 个、Agent Orchestration Context 12 个测试无失败，Agent Server 编译通过；固定故障测试覆盖 PRE_TURN 写前/写后、模型决策后、Tool 执行标记后、Tool 结果后、POST_TURN 后、终态写前/写后、取消恢复和未知写 Tool；Checkpoint/journal 通过 JSON 边界往返，隔离 PostgreSQL 17 顺序执行 V1～V10 并确认恢复表及唯一约束；
- `agent-reliability-v1` 使用真实 Content → Outbox/Kafka → Worker → Elasticsearch → Agent 链路，12 次请求可用性 `1.0`，P95 `226.402 ms`，Fallback Rate `0.0`；
- 单写者、fencing 单调、重复请求无额外 Run/Tool 事件、终态 Outbox、幂等审计消费、Shadow 主结果不变和快速关闭全部为 `true`；
- 固定单测证明旧 owner 不能提交、另一个实例写取消能停止 Loop、模型/Tool 故障稳定回退、Bulkhead 饱和快速拒绝；
- OpenAI-compatible 本地协议测试验证 usage 解析和价格换算；默认确定性 Provider 明确记录 `providerUsageMeasured=false`、Token/成本为 0，没有伪造付费模型数据；
- LongCat-2.0 本地功能验收得到 `RESULTS_READY / AGENT`，模型调用 Search Tool 后返回 1 条已发布内容，`providerUsageMeasured=true` 且 Web 展示同一真实候选；该单次验收只证明协议与链路可用，不作为质量或成本基线；
- Web `npm test` 的构建及 3 个渲染/桥接测试通过；浏览器验收确认 AI 搜索入口、会话 Composer、真实结果与稳定降级结果都由后端响应驱动，并修复首屏 Feed 晚返回夺取 AI 模式及顶部栏遮挡首条消息的问题；
- `./seekflux.sh status` 验收 PostgreSQL、Redis、Kafka、Elasticsearch、MinIO、三个 Server、Worker 与 Web 全部在线。

## Ark-Leto 对照后的剩余边界

核心执行红线已完成：固定主链、先获权后提交、强恢复、fencing 全区间、owner-CAS、清理顺序、事件分层、分布式取消、有限并发和确定性回退。完整矩阵见 [ADR-006](../adr/ADR-006-agent-reliability-fencing-outbox-shadow.md)。

仍未实现的能力包括：steer 排队语义、写 Tool 的持久副作用账本和外部对账、上下文压缩、OutputGuard 修复、HITL、Handoff、子 Agent、MCP、Chained/Graph Agent 和实时 Push。它们不是 Step 7 完成条件。当前已经能从模型前后、Tool 状态和终态安全边界继续，但 AR-3 只自动恢复 `READ_ONLY/IDEMPOTENT` Tool；`MUTATING + UNKNOWN` 会保留恢复事实并持续失败关闭，直到 AR-4 提供回执查询、补偿或人工 reconciliation。线程中断仍不能撤销外部系统已经发生的写副作用。

真实 Provider 已做单次本地功能联调，但 Token/成本/质量基线仍未建立。仓库已经具备计量、定价、Trace、Metrics 与报告字段；后续必须用固定数据集、固定 Provider/模型/Prompt 版本另生成可复现的运行环境基线，不能用一次成功请求替代评测。

## 如何验证

```bash
mvn -pl platform/agent-runtime,contexts/agent-orchestration-context,apps/agent-server,apps/worker-runner -am test
npm --prefix apps/web test
npm --prefix apps/web run lint
python3 -m py_compile evals/run_agent_reliability_eval.py
bash -n deploy/local/stack.sh
./seekflux.sh up
python3 evals/run_agent_reliability_eval.py
git diff --check
```

## 本阶段可以学到什么

- 租约解决“谁现在可以执行”，fencing 才解决“旧 owner 还能否晚到提交”；
- 恢复必须从权威事件源强读，Redis 热投影不能充当真相；
- Outbox 的价值是让业务终态和可传播事实同生共死，消费者幂等负责至少一次投递；
- Shadow 的第一原则不是候选更聪明，而是候选无论怎样失败都不改变主链；
- 可观测的 0 比伪造的 Token/成本更可信，确定性 Provider 与付费 Provider 的基线必须分开。

## 下一步

本阶段完成时的下一步是 Step 8“曝光与行为闭环”，该切片现在已经完成。当前状态和完成门槛以[学习路线首页](README.md)为准。
