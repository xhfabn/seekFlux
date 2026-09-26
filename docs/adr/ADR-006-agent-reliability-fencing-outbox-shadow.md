# ADR-006：Agent 多实例可靠性、事务事实与 Shadow 治理

- 状态：Accepted
- 日期：2026-08-10
- 最近更新：2026-09-16

## 背景

Step 6 的 Runtime 能处理复杂 Query 和多轮约束，但 Redis 租约本身不能阻止已经失去租约的旧实例晚到提交；进程内取消和 Shadow 开关也不能跨实例生效。Agent 终态如果只写 Session 而不产生可靠事件，评测和审计消费者无法在故障后重放。

参考《Ark-Leto 框架内核 与 Agentspark 主链路 原理详解》中的执行权、强恢复、事件分层、取消顺序和有限并发原则，本阶段补齐当前 Search Agent 在多实例部署下必须满足的不变量。项目仍是自研 Runtime，不依赖 Ark-Leto 二进制或源码。

## 决策

1. Redis 获取执行权时原子递增每个 Session 永不过期的 fencing 计数器，租约值保存 `owner|fencingToken`；续租和释放都使用 owner-CAS Lua。
2. fencing 覆盖从 Ingress 到终态提交的整个持权区间。PostgreSQL Session 保存 `active_fencing_token`，状态补丁和终态提交都拒绝旧 token；Loop 返回后必须再次续租成功才能提交结果。
3. 新 owner 获权后从 PostgreSQL `workspace_events` 强一致重放 Session。若相同 `requestId` 已提交但 Session 仍为 `EXECUTING`，更高 fencing token 可以认领该轮并创建新的 Run attempt；普通重复请求仍返回 409。
4. 取消信号写入 Redis 并带发生时间和稳定原因；任务只响应晚于本次启动时间的信号。`USER_CANCEL | STEER | AUTHORITY_LOST | SHUTDOWN` 采用 first-cause-wins，经 Session → batch → Tool token 向下传播；Runtime 在等待模型/Tool Future 时有界轮询并通过线程中断停止在途调用，晚到结果不得推进状态。取消是独立 `CANCELLED` Outcome，不得伪装成失败或回退。优雅停机先停止接收新执行、固化本地 `SHUTDOWN`、广播取消、等待受控宽限期，再关闭调度器。清理顺序保持“移除本机 token → owner-CAS 释放租约”。
5. Session 终态、`WorkspaceEvent` 和 Agent Outbox 在同一 PostgreSQL 事务中提交。Outbox 事件 ID 由 Session 与事件位置确定性生成；Kafka 审计消费者以 `eventId` 主键幂等写入。
6. 模型与 Tool 使用独立 Semaphore Bulkhead，饱和时快速返回稳定错误；模型、Tool、执行权丢失和跨实例取消均有固定故障测试。Tool 声明 `READ_ONLY | IDEMPOTENT | MUTATING` 效果类型，当前 Search Tools 均为 `READ_ONLY`，Tool Call ID 由请求和规范化参数确定性生成。
7. OpenAI-compatible Adapter 解析 Provider usage，并按配置价格计算微美元；Trace 和 Micrometer 指标关联 Agent、Prompt、Provider 与 Tool Schema 版本。默认确定性 Provider 不报告 Token，不伪造成本。
8. Shadow 使用与主链隔离的有界执行器，候选异常、超时或队列饱和都不得改变主结果。采样开关保存在 Redis，管理 API 的关闭对其他实例下一次请求生效；对比结果异步写入 PostgreSQL。
9. macOS 本地中间件通过 launchd 托管 Kafka、Elasticsearch 和 MinIO，避免启动命令退出后子进程被回收，保证固定评测可重复运行。
10. Runtime Checkpoint、pending Tool journal 与 Workspace snapshot 分责：Checkpoint 保存版本化可恢复运行态和 Workspace cutoff；journal 以稳定 call ID 记录决策、提交、未知和结果状态。恢复统一经过受 fencing 保护的 `ResumeIngress → ResumeAction`，先续租并强读 Workspace，再把遗留执行态原子转为未知后决定复用、重试或失败关闭。`READ_ONLY/IDEMPOTENT` 可用稳定 Call ID 自动恢复；`MUTATING` 使用下一条定义的账本和对账协议。
11. `MUTATING` Tool 必须同时通过注册策略和运行时执行策略，并且只允许在持久副作用账本可用时执行。Runtime 先以稳定 Tool Call ID 生成幂等键，按 `PREPARED → EXECUTING → SUCCEEDED/FAILED` 记录请求摘要、结果摘要和外部回执；接管时把遗留 `EXECUTING` 转成 `UNKNOWN`。`UNKNOWN` 只能通过 Tool 专属的外部状态查询、补偿或人工处置收敛为 `RECONCILED`，禁止再次发起原写操作。`NEED_APPROVAL` 先作为稳定策略结论失败关闭，真实挂起/恢复状态机留给 AR-8。
12. Ingress 必须显式声明 `NEW_EXECUTION | STEER | QUEUE`，不从 busy 状态猜测用户意图。普通新请求在已有 owner 时仍返回 BUSY；STEER 必须先以 `QUEUED_USER_MESSAGE` 持久化，再用同一发生时间广播取消；QUEUE 仅允许已挂起 Session，不取消在途任务。队列按 Session 有界、FIFO、请求幂等。当前 owner 在 segment 收敛后继续持权并原子提升当时全部队列项，每条消息进入历史，最后一条提供本批当前身份、瞬态上下文和状态补丁；请求级 override 不跨 segment 继承。提升后崩溃从正式 Workspace 事实恢复，失权由新 owner 继续，清理只删除不晚于本批 cutoff 的旧 STEER，不能删除用户取消或更晚信号。
13. ContextEngine 使用显式 Layer、完整消息/Tool Schema 预算与版本化增量摘要。压缩只在完整 turn 边界推进连续 inclusive cutoff，ASYNC 必须 single-flight 且运行在有界 executor；硬兜底必须生成 Skeleton 摘要，不允许留下无覆盖空洞。同步 Provider 的 400/413 仅在无输出时进入有限强压缩重试；OutputGuard repair 次数有硬上限并传播取消，耗尽得到稳定 degrade 或 fail。

## Ark-Leto 反向核对

| 参考不变量/能力 | SeekFlux 状态 | 说明 |
| --- | --- | --- |
| `Router → FeaturePipeline → SessionExecutor → AgentLoop` | 已实现 | Runtime Core 保持业务无关，Search 语义在 Context/Adapter |
| 先 acquire，再提交 UserMessage | 已实现 | busy 不产生 Ingress 事实 |
| owner-CAS 续租/释放与 fencing 全区间保护 | 已实现 | Redis Lua + PostgreSQL `active_fencing_token` + 提交前最终校验 |
| 接管前强一致恢复 | 已实现 | PostgreSQL Workspace 强读后校验 Checkpoint cutoff；崩溃中的相同请求可由高 token 认领 |
| 本地 token 先移除，再释放执行权 | 已实现 | `SessionExecutor.finally` 固定清理顺序 |
| Workspace/Run/Push 三类事件分责 | 部分实现 | Workspace 已持久化完整 User/Assistant/ToolResult 历史，Run 独立持久化；项目按既定同步 JSON 边界尚不提供流式 Push |
| 分布式 cancel 与在途调用停止 | 已实现 | 原因化 Redis 信号按运行起始时间过滤；真实 Loop 的模型前/中、Tool 中、跨实例和停机测试通过，Run/Trace/Push/HTTP 使用同一原因 |
| steer 先入队、再 cancel | 已实现 | 显式 STEER/QUEUE、持久有界 FIFO、批量 drain、同时间戳取消、fencing 提升和崩溃/失主恢复；等待队列的 HITL 恢复规则留给 AR-8 |
| Tool Schema、动态工具、并行调用、部分成功 | 已实现 | 共同 Deadline、稳定调用 ID、候选复用和 Bulkhead |
| Checkpoint 精确恢复 pending Tool Call | 已实现 | PRE/POST/终态 Checkpoint + Tool journal；结果复用、安全重试，未知写 Tool 转交副作用账本对账 |
| Mutating Tool 副作用账本 | 已实现 | V11 持久账本、稳定幂等键、外部回执、注册/执行双策略和 `UNKNOWN → RECONCILED`；无对账器时持续失败关闭 |
| 上下文分层压缩与超长重试 | 已实现 | 显式 Layer、完整消息/Tool Schema 计量、V13 增量摘要、no-gap cutoff、NONE/ASYNC/SYNC 和首输出前 400/413 强压缩重试 |
| OutputGuard 自动修复 | 已实现 | accept/有限 repair/degrade/fail，repair 传播取消且非法原文不进入 Assistant 历史 |
| eager dispatch | 未实现 | 与流式 Tool Call delta 一起留给 AR-7，必须晚于参数完整性和副作用安全检查 |
| HITL、异步等待点、Handoff、子 Agent | 未实现 | 不是当前 Search Agent 主链需要，后续按具体产品场景决定 |
| Skill/ToolGroup、MCP、Chained/Graph Agent | 未实现 | 当前请求级动态 Tool 集已足够；不会为框架完整度提前引入 |
| SSE/WebSocket 与跨 Pod Push 中继 | 未实现 | 用户已选择普通同步接口；若以后需要长任务进度再独立设计 |

因此，“阶段 7 完成”只表示多实例可靠性与平台治理切片完成，不表示参考文档列举的所有可选 Agent 形态都已经实现。

## 后续演进

- 2026-09-18：Skill/ToolGroup 与请求级 Capability Snapshot 已按 ADR-013 落地；上表保留 ADR-006 决策时点的原始状态。
- 2026-09-19：MCP Tool Source、协议客户端、动态注册与信任边界已按 ADR-014 落地。
- Chained/Graph Agent 与流式 Push 中继仍未实现，分别保留为 AR-9C 与 AR-8C 后置可选项。

## 后果

- 旧 owner 即使继续运行也不能污染 Session 终态或 Outbox；Redis 故障时续租失败，主链按失主处理而不是冒险提交。
- Runtime 产出 Outcome 是取消与正常完成的线性化点；该点前观察到的取消优先并丢弃晚到模型/Tool 结果，该点后新到的取消不反向改写已完成结果。线程中断只提供协作式停止，不能撤销外部系统已经发生的写副作用。
- Session 是唯一权威业务状态，Run attempt 可以保留失主和接管诊断记录；审计消费者可从 Outbox 重放。
- Shadow 可跨实例快速关闭且不增加主链失败率，但当前管理 API 仍是内部接口，生产部署前必须接入平台鉴权与变更审计。
- 现有 Search Tool 全部只读，接管可以复用已知结果或使用同一 call ID 安全重试。发布、支付或通知类 Tool 只有显式通过注册权限、配置持久账本并实现符合外部系统能力的 reconciliation 后才能接入；账本解决的是可判定恢复，不提供通用分布式事务，也不能替外部系统制造其原本不具备的幂等或查询能力。
- STEER 具有明确的线性化顺序：持久入队成功是接收事实，随后取消只负责加速当前 segment 收敛；即使进程在两者之间失败，消息仍可恢复。批量 drain 降低连续插话的重复模型调用，但最后意图覆盖意味着调用方应把不可合并的指令拆成需要逐条执行的任务，而不是依赖每条消息单独启动 segment。
- 真实付费 Provider 的价格和 Token 基线依赖部署方端点与密钥；仓库只保留协议测试、计量实现和不伪造数据的确定性基线。
- 上下文摘要当前直接从 PostgreSQL 共享事实读取，没有 Redis 热投影；确定性 Skeleton 优先保证 no-gap 和可恢复性，不把摘要质量冒充真实模型总结效果。同步调用的溢出重试不会重复已输出内容；流式首 chunk 之后的失败隔离仍属于 AR-7。
