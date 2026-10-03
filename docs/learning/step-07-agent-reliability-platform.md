# Step 7：Agent 可靠性与平台化

## 本阶段状态

- 状态：已完成
- 完成日期：2026-08-10
- 对应开发 Step：Step 7
- 对应 Agent Phase：Phase 3
- 对应决策：[ADR-006：Agent 多实例可靠性、事务事实与 Shadow 治理](../adr/ADR-006-agent-reliability-fencing-outbox-shadow.md)、[ADR-011：Agent 流式 Push 与 Eager Tool 安全边界](../adr/ADR-011-agent-streaming-push-and-eager-tool-safety.md)、[ADR-012：Agent 持久等待、幂等决议与恢复边界](../adr/ADR-012-agent-durable-wait-and-resume.md)、[ADR-013：Agent 能力快照、作用域与 ToolGroup 路由](../adr/ADR-013-agent-capability-snapshot-and-routing.md)、[ADR-014：MCP Tool 来源、信任边界与恢复语义](../adr/ADR-014-mcp-tool-source-and-trust-boundary.md)、[ADR-015：Agent Runtime 公共制品与宿主实现边界](../adr/ADR-015-agent-runtime-publication-boundary.md)
- 对应契约：[`contracts/openapi/seekflux-v1.yaml`](../../contracts/openapi/seekflux-v1.yaml)、[`agent-workspace-message-v1.schema.json`](../../contracts/events/agent-workspace-message-v1.schema.json)、[`agent-recovery-v1.schema.json`](../../contracts/events/agent-recovery-v1.schema.json)、[`agent-tool-side-effect-ledger-v1.schema.json`](../../contracts/events/agent-tool-side-effect-ledger-v1.schema.json)、[`agent-steer-queue-v1.schema.json`](../../contracts/events/agent-steer-queue-v1.schema.json)、[`agent-context-compaction-v1.schema.json`](../../contracts/events/agent-context-compaction-v1.schema.json)、[`agent-context-event-v1.schema.json`](../../contracts/events/agent-context-event-v1.schema.json)、[`agent-push-frame-v1.schema.json`](../../contracts/events/agent-push-frame-v1.schema.json)、[`agent-wait-lifecycle-v1.schema.json`](../../contracts/events/agent-wait-lifecycle-v1.schema.json)、[`agent-capability-lifecycle-v1.schema.json`](../../contracts/events/agent-capability-lifecycle-v1.schema.json)、[`agent-mcp-lifecycle-v1.schema.json`](../../contracts/events/agent-mcp-lifecycle-v1.schema.json)
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
- 接管统一执行 renew → restoreFresh → cutoff 校验 → commitResume → dispatch：安全 Tool 复用结果或按稳定 call ID 重试；写 Tool 使用 fencing 持久账本、稳定幂等键和外部回执，`UNKNOWN` 只做状态查询/补偿并写 `RECONCILED`，不重复原写请求；
- Ingress 显式区分 `NEW_EXECUTION / STEER / QUEUE`：忙碌时普通新请求仍返回 BUSY；STEER 先把消息持久化到有界 FIFO，再以同一时间戳广播取消；QUEUE 只接受已挂起 Session，不中断当前任务；
- owner 在当前 segment 收敛后继续持权 drain，批量提升当时全部排队消息；每条 UserMessage 都进入完整历史，最后一条请求提供当前身份、瞬态上下文和状态补丁。提升后崩溃可从已提升 Workspace 事实恢复，失权时由新 owner 在下一次受理中继续；
- 显式上下文 Layer 与无状态 Renderer 对稳定前缀、Agent 指令、动态 Tool Schema、Workspace、摘要、完整历史和本轮 recall 统一计量；长会话按完整 turn 压缩，不拆 Tool Call/Result；
- PostgreSQL V13 保存版本化增量摘要与连续 inclusive cutoff；支持 `NONE/ASYNC/SYNC`、ASYNC single-flight、受保护轮次、确定性 Skeleton hard fallback 和无摘要截断禁令；
- Provider 首输出前的 400/413 上下文溢出进入 `OVERFLOW_FALLBACK` 强压缩且有界重试；结构化输出通过有限 accept/repair/degrade/fail，repair 全程响应同一取消 token；
- Provider-neutral `ChatChunk` 支持 content/reasoning/usage/finish 和按 index 分片的 Tool Call；OpenAI-compatible Adapter 使用真实 SSE，区分空流、首 chunk 超时、输出前失败、输出后中断和流中取消，并且只在首个可见输出前重试一次；
- Push 覆盖 Segment、Loop、LLM turn、内容/reasoning、Tool delta/start/result、Checkpoint、排队/Steer、Control、错误与终态；`PushFrame` 使用 Session 单调 sequence，有界 history、订阅队列、Session 数量和 SSE 执行器，慢消费者溢出后断开；
- Redis Lua 把 sequence 分配和 Pub/Sub 发布原子化，`sourceId` 阻止跨实例回环；SSE `Last-Event-ID` 只做 replay/订阅，不重复提交 Agent 执行，历史缺口显式发送 `REPLAY_GAP`；
- 参数完整、Schema 与 Tool 策略通过后的 `READ_ONLY/IDEMPOTENT` Tool 可 eager 启动，并用稳定 call ID 与最终 Decision 精确匹配、按 index 合并；`MUTATING` Tool 坚持先落 Decision journal 和副作用账本，不做流内 eager；
- `WAITING` Runtime 终态和 `SUSPENDED` Session 投影支持 HITL、Async、Waitpoint、Handoff、Child 五类 WaitState；Checkpoint、WAITING journal 与 pending wait 原子保存，挂起/恢复 Workspace 事实和 Push 分层记录；
- wait resolution 在 execution authority、fencing 和行锁下 first-writer-wins；同一 resolution ID/内容/actor 忽略服务端接收时间差异而幂等，callback/timeout/取消竞态不重复 Tool；HITL 通过后按原 call ID 执行，Async 完成直接补 ToolResult；
- HITL/Tool 等待使用独立 deadline，执行预算在挂起时冻结；有界后台扫描处理超时，Session cancel 直接解析持久等待，恢复终态后继续 drain 等待期间的 QUEUE；
- 版本化 Skill/ToolGroup Catalog、AgentDef 最大权限、Session 持久激活和请求级 ephemeral Skill 共同解析为不可变 `CapabilitySnapshot`；Catalog/Skill/Group/Tool Schema 版本与指纹进入 Checkpoint/Trace，Queue drain 在安全边界重新解析；
- MCP server 通过 `2025-11-25` Streamable HTTP Tool 子集接入普通 `AgentTool`；按 source 原子发现/
  注销、namespaced 名称、受限远端 Schema、本地 effect/审批/tenant/user allowlist、取消、响应上限、
  per-server bulkhead/熔断和 Actuator health 形成独立信任与故障边界；
- CapabilitySnapshot v2 冻结 execution 开始时实际注册的 Tool 集，MCP Tool Schema version 覆盖
  server config、本地 policy、远端 Schema 和 status Schema hash；v1 快照仍可按旧指纹恢复；
- `MUTATING` MCP Tool 继续走持久副作用账本，未知结果不因重连重放；只有显式配置且 Schema 合法的
  status Tool 可按幂等键/Call ID 对账，否则保持 UNKNOWN；
- `switch_tool_groups` 属于 always-active 控制组，只接受冻结 Catalog 中的 group，当前 batch 结束后才改变下一模型轮的 Tool Schema；ephemeral 同名 shadow 不合并全局指令或 required Tool，Context 分开 active/ephemeral/lazy 层；
- Provider 请求传播 request/run/可选 W3C traceparent，支持受校验的请求级 model override；版本包含端点，usage 增加 cached input/reasoning token；当前单端点复用一个宿主管理的 HttpClient，不建立无上限动态 client cache；
- Tool 默认注册策略拒绝 `MUTATING`，显式授权后仍须通过运行时 `ALLOW/MODIFY/DENY/NEED_APPROVAL` 策略和账本能力检查；每个并行调用使用独立 ToolContext，并产生 before/after/failure 观察；
- 四类 Agent 终态 Topic 与按 `eventId` 幂等的审计消费者；
- 模型/Tool 独立 Bulkhead、稳定错误、确定性 Tool Call ID 和效果类型；
- 模型、Tool、失主、跨实例取消及 Bulkhead 故障测试；
- OpenAI-compatible usage 解析、价格换算、Trace 字段和版本化 Micrometer 指标；
- 隔离执行器中的 Shadow、PostgreSQL 对比记录、Redis 跨实例开关和管理 API；
- OpenAI-compatible 响应兼容标准 `message.content` 与 LongCat `message.reasoning_content`，两种结构都保留真实 usage；
- Agent Runtime 内核完成 DDD 物理分层：Application 只保留 `api`、`command`、`spi/business`、`spi/capability` 对外契约；领域模型按 Agent Definition、Decision、Run、Session、Tool、Feature、Execution 细分；Runtime、Router、Loop、Feature、Context、Tool、Execution、Shadow 编排进入 `domain/service`，默认 SPI 实现进入 `infrastructure`；外层 Agent Server 的 HTTP API 对应系统 `interfaces/rest`；
- Runtime 公共边界在 2026-09-26 进一步收敛：`seekflux-agent-runtime-core`
  为 Java 21 零第三方主依赖内核，Redis 执行权、取消和 Shadow 实现迁入
  `contexts/agent-orchestration-context/infrastructure`；新增的
  `seekflux-agent-runtime-spring-boot-autoconfigure` 只传递 Core，Spring Boot 依赖为 optional。
  Runtime/Context 映射、Search Tool、Direct Fallback、投影、Provider、MCP 和指标仍由
  SeekFlux 宿主 Adapter 持有（RC1 历史边界）；2026-10-02 开发版本将通用 MCP 迁入 Core，
  允许受控 Jackson 依赖，见 [ADR-016](../adr/ADR-016-default-mcp-in-runtime-core.md)；
  Agent Server 继续只保留 REST、启动和组合装配；
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
8. `MUTATING` Tool 在外部请求前写 `PREPARED/EXECUTING`；外部返回后先写账本结果/回执，再推进 journal、Checkpoint 和 Session。接管或取消把遗留执行态变为 `UNKNOWN`，只能对账，不能自动重放。
9. STEER 先在 PostgreSQL 追加 `QUEUED_USER_MESSAGE`，成功后才写 Redis `STEER`；执行中的 segment 取消收敛后，owner 用当前 fencing token 原子提升 FIFO 批次为正式 UserMessage，再清除不晚于批次 cutoff 的旧 STEER 并执行下一 segment。用户取消和更晚的 STEER 不会被误删。
10. ContextEngine 在一次 assemble 内固定摘要快照，按预算选择 noop、异步或同步压缩；HTTP 400/413 只在没有模型输出时触发有限强压缩重试。非法结构化 Decision 只做有限 repair，耗尽后返回稳定降级或失败，不把非法文本写入 Assistant 历史。
11. 流式 Provider 在首个可见 chunk 前允许一次有界传输重试；一旦 content/reasoning/tool delta 已发给 Push，后续断流或取消都不会重试。空流和首包超时使用独立错误码；流式输出已经可见时 OutputGuard 不再发起隐藏 repair 调用。
12. Redis 原子分配 Push sequence 并发布 frame；本地和远端订阅只保留有界窗口。新 SSE 请求创建执行，携带 `Last-Event-ID` 的请求只重放和等待终态，绝不再次执行同一 command。
13. 安全 Tool 的 eager Future 在最终 Decision 不匹配、取消或超时时中断；最终匹配后复用同一 Future 和稳定 Tool Call ID。写 Tool 始终走既有 journal/ledger 顺序。
14. Tool 策略返回 `NEED_APPROVAL` 或 Tool 返回 WaitRequest 时，Runtime 先原子保存 suspended Checkpoint、WAITING journal 与 pending wait，再追加 Assistant/WaitSuspended；外部决议经 `Router.resume` 恢复，补 ToolResult 后继续 Loop。
15. Wait 的合法结论按类型收口：HITL 只允许 approve/deny，异步类只允许 completed；timeout/callback first-writer-wins。执行预算不计算外部等待时间，pending 丢失时 HITL/Waitpoint/Handoff fail-fast，Async/Child 可从 Checkpoint 重建。
16. CapabilityResolve 在 Session/Agent 解析后冻结能力快照；持久变更只由版本化 Control API 写入 Workspace。每轮模型只看到快照内 Tool，实际执行仍由注册表/Schema/策略二次校验；切组结果从下一模型轮生效，恢复缺版本时失败关闭。
17. MCP 启动发现失败或连接失效会原子摘除对应 source；新 execution 不再看到它。旧 execution 只按
    冻结 Schema version 调用，热更不兼容时失败关闭；本地取消中止 HTTP 并尽力通知 peer，写调用
    传输未知只进入 ledger reconciliation，不自动重试原副作用。

## 关键代码入口

| 入口 | 作用 |
| --- | --- |
| `platform/agent-runtime/.../application/api/Router.java` | 业务调用 Runtime 的公开 API |
| `platform/agent-runtime/.../application/command/` | AgentRunRequest、FeatureRequest 等输入命令 |
| `platform/agent-runtime/.../application/spi/business/` | Planner、Tool、Feature、Context 等业务定制 SPI |
| `platform/agent-runtime/.../application/spi/capability/` | Session、LLM、执行权、记录和事件等能力 SPI |
| `platform/agent-runtime/.../domain/service/execution/SessionExecutor.java` | fencing、续租、恢复、跨实例取消、优雅停机 |
| `platform/agent-runtime/.../application/command/AgentIngressMode.java` | NEW_EXECUTION、STEER、QUEUE 显式入口语义 |
| `platform/agent-runtime/.../domain/service/execution/SteerQueuePolicy.java` | 持久队列容量策略 |
| `contexts/agent-orchestration-context/.../infrastructure/redis/RedisExecutionAuthorityStore.java` | SeekFlux 宿主的原子 fencing 计数与 owner-CAS Lua |
| `platform/persistence/.../JdbcAgentSessionStore.java` | 受 fencing 保护的消息/Outcome/Outbox 事务与 Workspace 重放 |
| `platform/persistence/.../WorkspaceMessageCodec.java` | 版本化 Assistant/ToolResult payload 的 JSON 边界映射 |
| `platform/agent-runtime/.../domain/model/recovery/` | Checkpoint、Tool journal、ResumeIngress/ResumeAction 领域契约 |
| `platform/agent-runtime/.../domain/service/recovery/AgentRecoveryExecution.java` | 安全点写入和固定崩溃注入边界 |
| `platform/persistence/.../JdbcAgentRecoveryStore.java` | fencing 下的 Checkpoint/journal 与原子 Resume 入口 |
| `platform/agent-runtime/.../domain/model/sideeffect/` | 写 Tool 账本状态、回执结果与 reconciliation 结论 |
| `platform/agent-runtime/.../application/spi/business/tool/` | Tool 注册策略、执行策略和外部状态对账 SPI |
| `platform/persistence/.../V11__agent_tool_side_effect_ledger.sql` | 写副作用长期账本、幂等键与状态检查约束 |
| `platform/persistence/.../V12__agent_steer_queue.sql` | 排队事件幂等、生命周期唯一性与 FIFO 查询索引 |
| `platform/agent-runtime/.../domain/service/context/DefaultContextEngine.java` | 分层组装、预算、完整 turn 压缩与 ASYNC single-flight |
| `platform/agent-runtime/.../domain/service/context/ContextRenderer.java` | 无状态消息渲染与完整消息 Token 估算 |
| `contexts/agent-orchestration-context/.../llm/openai/OpenAiCompatibleLlmClient.java` | 400/413 分类与 OutputGuard repair/degrade/fail |
| `platform/agent-runtime/.../application/spi/capability/llm/model/ChatChunk.java` | Provider-neutral 流式增量协议 |
| `platform/agent-runtime/.../domain/service/llm/ChatStreamAssembler.java` | chunk 顺序、Tool 分片和 usage 组装 |
| `platform/agent-runtime/.../infrastructure/event/DefaultPushEventStream.java` | 有界 replay、背压、Session sequence 与订阅生命周期 |
| `contexts/agent-orchestration-context/.../infrastructure/event/RedisPushEventRelay.java` | 原子 sequence + Pub/Sub 跨实例中继 |
| `apps/agent-server/.../interfaces/rest/AgentSearchController.java` | SSE 新执行、Last-Event-ID 重连和最终响应 |
| `platform/agent-runtime/.../application/spi/business/planner/model/EagerToolDispatcher.java` | 完整参数后的受控 eager 派发协议 |
| `platform/persistence/.../JdbcContextCompactionStore.java` | 共享增量摘要、Session 行锁和 no-gap cutoff |
| `platform/persistence/.../V13__agent_context_compactions.sql` | 版本化上下文摘要表与最新摘要索引 |
| `platform/agent-runtime/.../domain/model/wait/` | WaitState、WaitRequest、WaitResolution 与合法决议语义 |
| `platform/agent-runtime/.../domain/service/wait/WaitTimeoutProcessor.java` | 有界超时扫描和确定性 timeout resolution |
| `apps/agent-server/.../interfaces/rest/AgentWaitController.java` | 幂等人工/异步等待决议入口与 actor 记录 |
| `platform/persistence/.../V14__agent_wait_states.sql` | pending/resolved wait 事实、唯一 pending 和到期索引 |
| `platform/agent-runtime/.../domain/model/capability/` | Skill、ToolGroup、Session 激活与冻结 CapabilitySnapshot |
| `platform/agent-runtime/.../domain/service/capability/CapabilityResolver.java` | 三层作用域解析、AgentDef 权限收缩和恢复版本校验 |
| `platform/agent-runtime/.../domain/service/tool/AgentToolRegistry.java` | 本地/远端 Tool 的 source 原子替换、批量注销和有限版本索引 |
| `platform/agent-runtime/.../infrastructure/tool/SwitchToolGroupsTool.java` | 只影响下一模型轮的合法 ToolGroup 控制 Tool |
| `apps/agent-server/.../interfaces/rest/AgentCapabilityController.java` | Session 持久能力查询与版本化 Control API |
| `platform/persistence/.../V15__agent_capability_activations.sql` | capability version 及 operation 幂等唯一索引 |
| `platform/agent-runtime/.../domain/service/execution/AgentCallGuard.java` | 模型/Tool Bulkhead 与故障注入边界 |
| `platform/agent-runtime/.../infrastructure/llm/ShadowingLlmClient.java` | 不影响主链的 Shadow 执行 |
| `contexts/agent-orchestration-context/.../infrastructure/redis/RedisShadowSettingsStore.java` | SeekFlux 宿主的跨实例 Shadow 开关 |
| `platform/agent-runtime/pom.xml` | 可独立发布的 Core；开发版 MCP 包允许 Jackson，RC1 历史制品零第三方主依赖 |
| `platform/agent-runtime-spring-boot-autoconfigure/` | 从宿主 Bean 组装 Runtime，只传递 Core |
| `.github/workflows/agent-runtime-release.yml` | 验证、签名并暂存两个 Maven Central 制品，不自动发布 |
| `contexts/agent-orchestration-context/.../infrastructure/runtime/AgentRuntimeExecutionAdapter.java` | Context 输出 Port 与 Runtime API 的业务映射 |
| `platform/agent-runtime/.../mcp/` | 默认 Streamable HTTP Client、连接治理、受限 Schema、Proxy 与扩展 SPI |
| `platform/agent-runtime-spring-boot-autoconfigure/.../AgentMcpProperties.java` | 公共、默认关闭的 MCP server/Tool 策略配置 |
| `platform/agent-runtime-spring-boot-autoconfigure/.../AgentMcpAutoConfiguration.java` | 按配置装配默认实现，宿主扩展 Bean 回退 |
| `apps/worker-runner/.../AgentOutcomeAuditWorker.java` | 幂等 Agent 终态审计消费者 |
| `evals/run_agent_reliability_eval.py` | 真实链路可靠性/SLO 固定评测 |

## 完成证据

- 2026-09-12 DDD 包结构调整后，Agent Runtime 23 个测试通过；包含 Agent Orchestration、Agent Server、Worker 及依赖在内的 17 个 Reactor 模块完整测试无失败；
- 2026-09-13 Adapter 按所有者收敛后，Agent Runtime 26 个测试、Agent Orchestration Context 7 个测试通过；包含 Server、Worker 及依赖在内的 17 个 Reactor 模块 clean 回归无失败；
- 2026-09-13 模型 Provider 所有权修正后，OpenAI-compatible Adapter 及其 3 个协议测试迁入 Agent Orchestration Context；Agent Runtime 23 个测试、Agent Orchestration Context 10 个测试通过，17 个 Reactor 模块共 76 个测试 clean 回归无失败；
- 2026-09-13 AR-1 取消闭环后，JDK 21 下 Agent Runtime 31 个测试通过；Agent Orchestration Context 及依赖、Persistence + Agent Server 及依赖两组回归通过。固定测试覆盖模型前取消、模型中断、Tool 中断、取消后禁止下一模型轮、跨实例 `USER_CANCEL`、停机 `SHUTDOWN`、失权禁止提交、OpenAI 调用中断及取消响应不触发 Direct Fallback；
- 2026-09-13 AR-2 消息历史闭环后，JDK 21 下 Agent Runtime 36 个、Persistence 3 个、Agent Orchestration Context 12 个测试无失败，Agent Server 编译通过；隔离 PostgreSQL 17 顺序执行 V1～V9 成功。固定测试覆盖跨 JSON 进程边界的第二轮历史恢复、稳定消息/Tool Call ID、并行顺序、取消 ToolResult、旧 UserMessage、未知字段兼容以及孤立/缺失结果拒绝；
- 2026-09-14 AR-3 精确恢复后，JDK 21 下 Agent Runtime 48 个、Persistence 5 个、Agent Orchestration Context 12 个测试无失败，Agent Server 编译通过；固定故障测试覆盖 PRE_TURN 写前/写后、模型决策后、Tool 执行标记后、Tool 结果后、POST_TURN 后、终态写前/写后、取消恢复和未知写 Tool；Checkpoint/journal 通过 JSON 边界往返，隔离 PostgreSQL 17 顺序执行 V1～V10 并确认恢复表及唯一约束；
- 2026-09-15 AR-4 副作用账本完成后，JDK 21 下 Agent Runtime 56 个、Persistence 6 个、Agent Orchestration Context 13 个测试无失败，`mvn test` 全仓 26 个 Reactor 模块回归通过；固定故障测试覆盖外部请求前、外部成功未确认、账本成功未推进 Session、reconciliation 后再次崩溃与重复恢复及外部写后异常，外部实际写入均保持 1 次且不确定异常保留 UNKNOWN；注册/运行时双策略、无账本拒绝、外部回执 JSON、并行独立 Context 和 Micrometer 低基数标签均有测试。隔离 PostgreSQL 17 顺序执行 V1～V11，并确认 Call ID/幂等键唯一及状态/结果检查约束；
- 2026-09-15 AR-5 Steer Queue/Drain 完成后，JDK 21 下 Agent Runtime 65 个、Persistence 7 个、Agent Orchestration Context 14 个测试无失败，`mvn test` 全仓 26 个 Reactor 模块回归通过；固定测试覆盖 busy 下显式 STEER、普通请求仍 BUSY、等待态 QUEUE、非等待态拒绝、有界容量、FIFO 批量提升、最后意图生效、请求级 override 清理、重复请求幂等、提升后崩溃恢复、drain 边界失主接管以及旧 STEER/真实取消隔离。隔离 PostgreSQL 17 顺序执行 V1～V12，并验证同一逻辑消息可从 QUEUED 生命周期转换为 USER 生命周期且各自唯一；
- 2026-09-16 AR-6 上下文治理完成后，JDK 21 下 Agent Runtime 71 个、Agent Orchestration Context 21 个测试无失败，`mvn test` 全仓 26 个 Reactor 模块回归通过；固定测试覆盖 Tool Schema 计量、完整 turn 与 Tool 配对、摘要 no-gap、ASYNC single-flight、noop/exhausted、400/413 重试及耗尽、OutputGuard repair/degrade/fail、repair 取消和稳定 Runtime 错误码。隔离 PostgreSQL 17 顺序执行 V1～V13，并真实插入版本化压缩摘要；
- 2026-09-16 AR-7 流式模型与实时 Push 完成后，JDK 21 下 Agent Runtime 81 个、Agent Orchestration Context 28 个、Agent Server 1 个测试无失败，`mvn -q test` 全仓 26 个 Reactor 模块回归通过；固定测试覆盖文本/reasoning/细分 usage、Tool Call 分片、参数完整后 eager、非法 Schema 禁止 eager、首 chunk 超时、空流、输出前单次重试且不重复 chunk、输出后断流不重试、流中取消、Push listener 隔离、replay gap、慢消费者溢出、Session 硬上限、跨实例 relay 防回环和 Last-Event-ID 不重复执行；
- 2026-09-17 AR-8A/AR-8B 完成后，JDK 21 下 `mvn -q test` 全仓回归通过；受影响模块 132 个测试无失败：Agent Runtime 92 个、Persistence 8 个、Agent Orchestration Context 28 个、Agent Server 4 个，Web build/lint 通过。固定测试覆盖审批前不执行/批准后只执行一次、Async callback 不重派、合法决议类型、接收时间变化的重复决议、timeout/late callback、等待中 cancel、超时扫描/single-flight、Session 投影、Checkpoint JSON 和协调器失败隔离；隔离 PostgreSQL 17 顺序执行 V1～V14 并确认 wait 约束与索引；
- 2026-09-18 AR-9A 完成后，JDK 21 下 `mvn -q test` 全仓 62 个报告、204 个测试全部通过；固定测试覆盖 Session auto-activate 持久化、ephemeral shadow/越权、always-active 控制组、真实 Search Agent ToolGroup 缩减、下一模型轮切组及快照指纹、损坏快照的派生 Tool/Group 自校验、Context 分层、Queue 序列化、Control API 和低基数 Metrics。隔离 PostgreSQL 17 顺序执行 V1～V15，确认 capability version 非负约束和 operation 唯一索引；
- 2026-09-19 AR-9B 完成后，`mvn -q test` 全仓 67 个报告、224 个测试全部通过；其中 Agent Runtime
  107 个、Agent Orchestration Context 44 个、Agent Server 8 个。协议级 Streamable HTTP server
  覆盖 initialize/session、SSE list、JSON call、取消通知与超大响应中止；受控 fake 覆盖断线/重连、
  source 批量注销、Schema 热更、名称冲突、tenant/user 隔离、bulkhead、熔断和 UNKNOWN 写结果。
  本阶段没有新增持久表，迁移保持 V1～V15；
- 2026-09-26 公共制品收敛后，Core 136 个主源码文件没有 JDK/SeekFlux 之外的
  import；独立消费者的 Maven 依赖树只有
  `seekflux-agent-runtime-spring-boot-autoconfigure → seekflux-agent-runtime-core`，没有 Spring Boot、
  Redis、JDBC、Jackson、Provider 或 MCP 传递依赖。Auto-configuration 5 个新测试覆盖
  宿主端口装配、只读 Tool 收集、默认拒绝写 Tool、配置关闭和缺失必需端口；
  `central-release` profile 已实际产出两个主 JAR、sources JAR 和 Javadoc JAR；
  JDK 21 下 `mvn -q test` 全仓 68 个报告、229 个测试全部通过。本轮没有新增持久表、
  HTTP API 或事件契约；
- 2026-09-27 仓库所有者选择 Apache-2.0；根 `LICENSE`、两个公共 POM 的许可证元数据和
  发布 workflow 的 JAR 许可证检查已补齐。JDK 21 下 `central-release` 本地打包成功；
  同范围 Maven 测试通过，两个主 JAR 和两个 sources JAR 均含 `META-INF/LICENSE`。
  本轮未改动 Java 逻辑，
  Central 命名空间、凭据、GPG 签名、真实 staging/publish 和外部消费者验收仍未完成；
- 2026-09-27 RC1 发布前复核：JDK 21 下全仓 `mvn -q test` 共 68 个报告、229 个测试，
  failures/errors/skipped 均为 0；`central-release` 打包成功，两个主 JAR、sources 和 Javadoc
  JAR 齐全，Core 的 `jdeps` 结果只有 `java.base`。Portal 显示 `io.github.xhfabn` Verified，
  GitHub Actions Repository secrets 存在四个要求的名称，所有者确认公钥已可从 keyserver
  检索。Secret 值不可见，实际 CI 签名与 Portal staging/publish 尚待验证；
- 2026-09-27 RC1 staging 验收：`codex/agent-runtime-publishable-v1` 的提交
  `e0f2139980028fa9b7215e53ef6bab2110d82cc4` 已由 Tag `agent-runtime-v1.0.0-RC1`
  触发[发布工作流](https://github.com/xhfabn/seekFlux/actions/runs/36301443634)，测试、公共
  制品边界、签名与上传步骤全部通过。Portal deployment
  `c65b92a7-cd6f-41f4-8a20-f71cbbd6c927` 为 `VALIDATED`，2/2 组件通过校验，
  每个组件均含 POM、主 JAR、sources、Javadoc、签名及校验和。最终人工 Publish 尚未执行，
  因而未做公开仓库空缓存消费者验收；
- 2026-09-27 RC1 公开消费验收：仓库所有者确认后已在 Portal 提交最终 Publish；两个
  Maven Central 公共 POM、主 JAR、sources、Javadoc 及各自签名共 16 个文件均返回 HTTP 200。
  在仓库外使用空 Maven 缓存，仅声明 Spring Boot
  Starter 和 Auto-configuration 的 JDK 21 消费者 `mvn compile` 成功，依赖树为
  `seekflux-agent-runtime-spring-boot-autoconfigure → seekflux-agent-runtime-core`；未给 Core
  额外引入 Spring Boot、数据库或 Provider。Portal deployment 最终显示 `PUBLISHED`；
- `agent-reliability-v1` 使用真实 Content → Outbox/Kafka → Worker → Elasticsearch → Agent 链路，12 次请求可用性 `1.0`，P95 `226.402 ms`，Fallback Rate `0.0`；
- 单写者、fencing 单调、重复请求无额外 Run/Tool 事件、终态 Outbox、幂等审计消费、Shadow 主结果不变和快速关闭全部为 `true`；
- 固定单测证明旧 owner 不能提交、另一个实例写取消能停止 Loop、模型/Tool 故障稳定回退、Bulkhead 饱和快速拒绝；
- OpenAI-compatible 本地协议测试验证 usage 解析和价格换算；默认确定性 Provider 明确记录 `providerUsageMeasured=false`、Token/成本为 0，没有伪造付费模型数据；
- LongCat-2.0 本地功能验收得到 `RESULTS_READY / AGENT`，模型调用 Search Tool 后返回 1 条已发布内容，`providerUsageMeasured=true` 且 Web 展示同一真实候选；该单次验收只证明协议与链路可用，不作为质量或成本基线；
- Web `npm test` 的构建及 3 个渲染/桥接测试通过；浏览器验收确认 AI 搜索入口、会话 Composer、真实结果与稳定降级结果都由后端响应驱动，并修复首屏 Feed 晚返回夺取 AI 模式及顶部栏遮挡首条消息的问题；
- `./seekflux.sh status` 验收 PostgreSQL、Redis、Kafka、Elasticsearch、MinIO、三个 Server、Worker 与 Web 全部在线。

## Ark-Leto 对照后的剩余边界

核心执行红线已完成：固定主链、先获权后提交、强恢复、fencing 全区间、owner-CAS、清理顺序、事件分层、分布式取消、有限并发和确定性回退。完整矩阵见 [ADR-006](../adr/ADR-006-agent-reliability-fencing-outbox-shadow.md)。

仍未实现的能力包括：真实 Handoff/子 Agent 产品 launcher、持久父子/源目标 Session 关系、父取消自动级联、Fork promotion 和 Chained/Graph Agent。`DelegatedAgentLauncher/ParentChildAgentCoordinator` 当前只是预算、深度、身份传播和结果回填协议，Fork 明确返回 `FORK_PROMOTION_UNSUPPORTED`，因此 AR-8C 保持后置可选。MCP 当前只实现 Streamable HTTP Tool 子集和静态启动配置，没有 Resources/Prompts/Sampling/Elicitation、OAuth 协商、server push、在线发布或管理端 refresh；默认关闭，第三方业务 server 仍需逐 Tool 做凭据、effect、审批、allowlist 和对账验收。Capability Catalog 当前由宿主静态装配，没有在线配置发布或旧版本制品仓库；跨部署恢复若缺少冻结版本会失败关闭。审批页面、租户授权和业务通知属于具体产品 Adapter；通用 API 只消费可信网关注入的 actor/tenant，不替代认证。AR-7 当前提供 SSE 而非 WebSocket，Push history 是有界瞬态投影而不是可审计事实；Redis 故障时本地执行继续，但跨实例实时订阅会降级，恢复仍依赖 PostgreSQL Workspace/Checkpoint。当前只有单端点 OpenAI-compatible Provider，因此复用一个 HttpClient；多端点会话粘性和有界动态 client pool 要在真实路由需求出现时进入 Provider Adapter。AR-6 仍使用确定性 Skeleton 摘要并直接读取 PostgreSQL，没有模型摘要器或 Redis 热投影。AR-4 已提供通用副作用账本和对账协议，但框架不提供跨外部系统分布式事务；每个真实写 Tool 仍必须依据目标系统实现可靠幂等、状态查询或补偿，无 reconciler 或外部仍无法判定时会保留 `UNKNOWN` 并持续失败关闭。

真实 Provider 已做单次本地功能联调，但 Token/成本/质量基线仍未建立。仓库已经具备计量、定价、Trace、Metrics 与报告字段；后续必须用固定数据集、固定 Provider/模型/Prompt 版本另生成可复现的运行环境基线，不能用一次成功请求替代评测。

公共 Core 提供 SPI、运行协议与默认 MCP Tool Client，不承诺默认数据库、Redis、模型厂商或观测
Adapter。宿主自行实现持久化时必须保留 fencing、幂等、原子提交、first-writer-wins 和
未知写结果不重放等不变量。RC1 已完成可发布构建、CI 签名、Portal staging 与公开消费验收；
仓库所有者已于 2026-09-27 选择 Apache-2.0，并补齐根 `LICENSE`、发布 POM 元数据和
JAR 内许可证。Portal deployment 已显示 `PUBLISHED`，公共仓库能够解析并编译这两个制品。

## 如何验证

### 2026-10-02：默认 MCP 迁入公开 Core（已完成）

- 分支事实：本地 `main` 从 `e689c9b` 快进合入 `codex/agent-runtime-publishable-v1` 到 `4b0eda0`，
  无冲突；新实现保留为 main 工作区未提交修改，没有推送、Tag 或新版本发布。
- 入口：Core 的 `mcp/` 保存默认 Client/Manager/Proxy 与可替换认证、附加授权、Schema、
  参数/结果/对账及事件接口；公共 `AgentMcpProperties/AgentMcpAutoConfiguration` 保存默认关闭
  的配置与 Bean 回退。业务 Context 不再持有协议实现，仍持有 Micrometer 适配。
- 失败/降级：发现失败只摘除对应 source；allowlist 不能被附加授权绕过；缺失凭据失败关闭；
  不支持的 Schema 类型/校验关键字明确拒绝；已取消调用不发送，重连发现复用原取消 token，
  调用预算计入发现和排队耗时；关闭后禁止重连。未知写结果仍不重放，业务回执/状态约定可替换。
- 版本：两个公共模块与根版本属性统一为 `1.0.0-RC2-SNAPSHOT`；MCP 包允许 Jackson，
  Domain/Application 仍不引用 MCP/Jackson。历史 RC1 的 Tag 和 Central 制品不变。
- 完成证据：JDK 21 `mvn -q clean test` 为 70 份报告、251 个测试，无失败、错误或跳过；
  其中 Core MCP 26 个测试包含本机真实 HTTP 交换、默认配置接入、取消通知、超大输出、
  扩展策略、Schema 指纹/约束与未知写结果不重放，公共自动配置含 8 个 MCP 装配测试。
  `mvn -q -pl platform/agent-runtime,platform/agent-runtime-spring-boot-autoconfigure -am install
  -Pcentral-release -Dgpg.skip=true -DskipTests` 成功；主包、sources、Javadoc 产出且许可证在主包中。
  `verify-core-boundary.sh` 通过；公共 MCP 自动装配类、Spring 元数据与 imports 清单在发布包中。
- 仓库外验收：纯 Java 消费者仅依赖 Core，编译/启动输出 `CORE_CONSUMER_OK`；
  Spring Boot 3.5.16 消费者仅声明 Boot Starter 与 Auto-configuration，实际自动发现并启动
  MCP Manager，输出 `BOOT_CONSUMER_OK`。它们使用本地开发快照与当前 Maven 缓存，
  不冒充 Central 新发布或空缓存验收；发现/调用的固定协议服务证据来自 Core HTTP 测试。
- 剩余边界与 API 责任见 [ADR-016](../adr/ADR-016-default-mcp-in-runtime-core.md) 和
  [MCP 接入契约](../../contracts/runtime/agent-mcp-v1.md)；未新增完整 MCP、OAuth、STDIO、
  Server 服务或第三方写工具幂等保证。未重跑检索/Agent 效果 Eval，不声称质量提升。
  本轮未开始新 Step，当前总体路线仍见[学习入口](README.md)。

```bash
mvn -pl platform/agent-runtime,platform/agent-runtime-spring-boot-autoconfigure,contexts/agent-orchestration-context,apps/agent-server,apps/worker-runner -am test
npm --prefix apps/web test
npm --prefix apps/web run lint
python3 -m py_compile evals/run_agent_reliability_eval.py
bash -n deploy/local/stack.sh
./seekflux.sh up
python3 evals/run_agent_reliability_eval.py
git diff --check
```

## 2026-10-03：MCP 包职责分类（已完成）

- 入口：`platform/agent-runtime/.../mcp/model|spi|connection|exception` 与
  `infrastructure/http|auth|schema|tool`；测试按组件对应分包，跨组件默认能力测试放 integration，
  新增 `architecture/McpPackageStructureTest` 固定包归属与契约签名。
- 结构变化：Schema SPI 返回独立 `McpTranslatedTool`；Proxy 使用 `McpToolCallGateway`，
  不依赖具体 Manager 类型；配置、Tool 策略、调用/事件数据和错误各有独立归属。
  Jackson 字节码门槛收紧到 HTTP/Schema 默认实现。
- 行为不变：Spring 属性键、协议版本、allowlist、取消、source 隔离、版本冻结与 UNKNOWN
  不重放规则保持原有语义；没有增加完整 MCP 能力。包名变化仅作用于未发布 RC2，RC1 不受影响。
- 验证：JDK 21 `mvn -q clean test` 产出 71 份报告、254 个测试，零失败、错误或跳过；
  现有协议级 HTTP 与故障测试继续通过。`install -Pcentral-release -Dgpg.skip=true -DskipTests`
  完成主包、源码与 Javadoc 构建，新包布局的 `verify-core-boundary.sh` 通过。
  仓库外 Java 与 Spring Boot 消费者更新 import 后重新编译/启动通过；只验证本地开发快照，
  不冒充 Central 发布或第三方 Server 验收。
- 当前类型归属见 [MCP 公共接入契约](../../contracts/runtime/agent-mcp-v1.md)，长期边界见
  [ADR-016](../adr/ADR-016-default-mcp-in-runtime-core.md)。未开始新 Step，未提交、推送或发布；
  当前总体进度仍以[学习入口](README.md)为准。

## 2026-10-03：增量消息事实、引用式恢复与状态基线（已完成）

- 核心入口：`AgentRuntime.recordToolDecision/recordJournalResult`、`AgentDecisionContext.messages`、
  `DefaultContextEngine`、`AgentSession.restore/snapshot`；宿主入口：`JdbcWorkspaceFacts`、
  `JdbcAgentRecoveryStore`、`JdbcAgentSessionStore.saveSnapshotIfNeeded`、V16。
- 进行中的 Assistant 与 DECIDED journal、ToolResult 与 terminal journal 在持权 Session 行锁事务
  中提交。结果按稳定 index 收集后逐个落库，不再等待批次全部返回；取消保留已提交的真实结果。
  内存 DTO 仍含已解析历史，但 Checkpoint v2 payload 仅含 state/metadata/terminal position；
  大正文、StepTrace 只追加一次，Journal 不再复制 Assistant/结果，Outcome/Outbox 仍原子收敛。
- `session_snapshots` 保存聚合状态、pending call IDs 和 Queue/Wait/当前输入的引用；默认每 100
  个事件，正整数 `seekflux.agent.session.snapshot-interval-events` 可调整，挂起/终态强制保存。
  冷恢复只对基线后的 tail 执行 reducer，历史正文按最新摘要 cutoff 和 pending 引用读取。
  Queue/promotion 崩溃恢复、历史 Wait 查询仍以持久数据为准，不绑定具体实例。
- 模型每轮可见 execution 内完整 Assistant/ToolResult，并按 message ID 排除与恢复 Session 的
  重复事实；不再重复注入 observations 文本。摘要正文与覆盖引用在同一事务提交后才切换，
  原文不删除；Redis 仍是 authority/cancel 与业务结果缓存，没有增加 Runtime 共享投影缓存。
- 失败边界：相同事件/Outcome ID 的不同内容失败；旧 fence 和 journal 失败事务不会留下半条
  消息；模型决策已提交或 Tool 结果已提交时不重复调用；模型返回但未提交任何事实时仍可重算。
  v1 自包含 Checkpoint/journal 继续兼容，不盲删旧 payload；写副作用仍必须走账本与对账。
- 验证：JDK 21 全仓 `mvn -q test -Dseekflux.test.jdbc-url=jdbc:postgresql://127.0.0.1:55437/seekflux_agent_facts_v2`
  为 73 份报告、268 个测试，零失败/错误/跳过；其中 11 个新 PostgreSQL 集成验收实际执行
  V1～V16、事务代理、进行中崩溃、部分并行完成、等待回调、队列/升格、压缩原文保留、冲突回滚
  和旧编码恢复。另有 3 个新增 Core 快照/上下文固定测试。数据库是本轮独立临时实例，未连接
  产品数据库；样本 v2 Checkpoint 引用 payload 最大 68 字节，不是容量/吞吐压测承诺。
- 发布边界复核：Core/自动装配的 `central-release` 打包（跳过签名、不 deploy）通过；
  `verify-core-boundary.sh` 检查 Core JAR 通过，仍不传递 JDBC/Redis/Spring。
  本轮文档本地链接和 `git diff --check` 通过；数据库变更只在隔离验收库执行。
- 长期事实来源：[ADR-017](../adr/ADR-017-agent-event-facts-and-reference-snapshots.md)、
  [持久化 v2 契约](../../contracts/runtime/agent-persistence-v2.md)。未做 token delta 落库、当前
  protected turn 激进压缩、归档、快照 GC 或性能 SLO；未重跑检索效果 Eval，不声称质量变化。
  未开始新 Step，未提交、推送或发布。当前总路线仍以[学习入口](README.md)为准。

## 2026-10-03：工具说明出口与冻结模型定义（已完成）

- 修复事实：`AgentTool.description()` 兼容默认空串，本地工具主动声明用途；
  `McpProxyTool` 透传 translated.description，MCP Translator 保留参数说明。
  `AgentToolParameter` 保留旧构造/factory，并新增 `withDescription`；说明均有长度边界。
- 单一来源：Registry 捕获不可变 `AgentToolDefinition`，Context Engine 同源生成模型 `tools`
  与文本能力层，删除占位用途；ToolGroup 用途/active/成员来自冻结快照并按允许范围过滤。
  工具注册不等于 Agent 授权，说明不覆盖 effect、审批或副作用策略。
- 恢复事实：新 DefinitionSnapshot/DecisionContext 携带完整模型定义，切组不丢失；恢复校验
  同版本定义一致，热更冲突原子拒绝。冻结定义经现有 EXECUTION_METADATA 持久化，Checkpoint
  仍只引用事实，不新增表。旧 Snapshot/参数 JSON 缺字段和旧 Java 构造器继续兼容。
- 升级：本地 Search/SwitchToolGroups 定义升 v2；MCP 版本是覆盖说明/Schema/配置/策略/对账
  的固定 68 字符 hash。旧开发版本不可静默替换，需先收敛旧执行或保留确切旧实现；
  RC1 公开制品不变。本轮未安装 SNAPSHOT 到 m2、未提交/推送/发布。
- 验证：JDK 21 全仓 `mvn -q test -Dseekflux.test.jdbc-url=jdbc:postgresql://127.0.0.1:55437/seekflux_agent_facts_v2`
  共 73 份报告、278 项测试，无失败/错误/跳过，含 11 项真实隔离 PostgreSQL 验收。
  新增 10 项测试，覆盖描述/参数投影、空描述兼容、热更冲突、长度/类型拒绝、恢复防漂移、
  旧 JSON 和真实 HTTP 模型请求；PG 增加非空说明持久恢复断言，修复固定 resolution ID 导致
  重复运行验收的唯一键冲突。未连接产品数据库，未重跑真实模型选择/检索质量 Eval。
- 发布边界复核：Core/自动装配 `central-release` 打包通过（跳过签名、不 deploy），
  Core JAR 的 `verify-core-boundary.sh` 通过；Markdown 本地链接、连续 Step 编号及
  `git diff --check` 通过。临时 PostgreSQL 验收实例在验证后关闭。
- 长期事实来源：[ADR-018](../adr/ADR-018-tool-descriptions-and-frozen-model-definitions.md)、
  [工具定义契约](../../contracts/runtime/agent-tool-definition-v1.md)。自定义 MCP Schema Adapter
  仍需自行覆盖模型语义版本；没有新增工具工厂、注解解析器、Prompt 注入检测器或在线版本仓库。
  当前总路线仍以[学习入口](README.md)为准，不创建新 Step。

## 本阶段可以学到什么

- 租约解决“谁现在可以执行”，fencing 才解决“旧 owner 还能否晚到提交”；
- 恢复必须从权威事件源强读，Redis 热投影不能充当真相；
- Outbox 的价值是让业务终态和可传播事实同生共死，消费者幂等负责至少一次投递；
- Shadow 的第一原则不是候选更聪明，而是候选无论怎样失败都不改变主链；
- 可观测的 0 比伪造的 Token/成本更可信，确定性 Provider 与付费 Provider 的基线必须分开。

## 下一步

本阶段完成时的下一步是 Step 8“曝光与行为闭环”，该切片现在已经完成。当前状态和完成门槛以[学习路线首页](README.md)为准。
