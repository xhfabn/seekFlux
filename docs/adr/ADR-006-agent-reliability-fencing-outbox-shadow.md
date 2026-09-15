# ADR-006：Agent 多实例可靠性、事务事实与 Shadow 治理

- 状态：Accepted
- 日期：2026-08-10
- 最近更新：2026-09-14

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
10. Runtime Checkpoint、pending Tool journal 与 Workspace snapshot 分责：Checkpoint 保存版本化可恢复运行态和 Workspace cutoff；journal 以稳定 call ID 记录决策、提交、未知和结果状态。恢复统一经过受 fencing 保护的 `ResumeIngress → ResumeAction`，先续租并强读 Workspace，再把遗留执行态原子转为未知后决定复用、重试或失败关闭。只有 `READ_ONLY/IDEMPOTENT` 可自动恢复；`MUTATING + UNKNOWN` 在副作用账本完成前禁止重试。

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
| steer 先入队、再 cancel | 未实现 | 当前没有 QueuedUserMessage/插话 API，不能把 `steer=true` 误称为完整 steer |
| Tool Schema、动态工具、并行调用、部分成功 | 已实现 | 共同 Deadline、稳定调用 ID、候选复用和 Bulkhead |
| Checkpoint 精确恢复 pending Tool Call | 已实现 | PRE/POST/终态 Checkpoint + Tool journal；结果复用、安全重试、未知写 Tool 失败关闭均有固定故障测试 |
| Mutating Tool 副作用账本 | 未实现 | 已有 Effect 元数据和稳定 ID，但尚无持久化幂等回执；引入写 Tool 前必须补齐 |
| 上下文分层压缩与超长重试 | 未实现 | 当前上下文规模有界，尚无摘要/裁剪/Provider 413 修复 |
| OutputGuard 自动修复、eager dispatch | 未实现 | 当前只做结构化 Decision 解析和一次 Tool 参数修复 |
| HITL、异步等待点、Handoff、子 Agent | 未实现 | 不是当前 Search Agent 主链需要，后续按具体产品场景决定 |
| Skill/ToolGroup、MCP、Chained/Graph Agent | 未实现 | 当前请求级动态 Tool 集已足够；不会为框架完整度提前引入 |
| SSE/WebSocket 与跨 Pod Push 中继 | 未实现 | 用户已选择普通同步接口；若以后需要长任务进度再独立设计 |

因此，“阶段 7 完成”只表示多实例可靠性与平台治理切片完成，不表示参考文档列举的所有可选 Agent 形态都已经实现。

## 后果

- 旧 owner 即使继续运行也不能污染 Session 终态或 Outbox；Redis 故障时续租失败，主链按失主处理而不是冒险提交。
- Runtime 产出 Outcome 是取消与正常完成的线性化点；该点前观察到的取消优先并丢弃晚到模型/Tool 结果，该点后新到的取消不反向改写已完成结果。线程中断只提供协作式停止，不能撤销外部系统已经发生的写副作用。
- Session 是唯一权威业务状态，Run attempt 可以保留失主和接管诊断记录；审计消费者可从 Outbox 重放。
- Shadow 可跨实例快速关闭且不增加主链失败率，但当前管理 API 仍是内部接口，生产部署前必须接入平台鉴权与变更审计。
- 现有 Search Tool 全部只读，接管可以复用已知结果或使用同一 call ID 安全重试；写 Tool 状态未知时保持恢复事实并失败关闭。未来允许发布、支付或通知类 Tool 自动恢复前，AR-4 外部回执、副作用账本与 reconciliation 是硬门槛。
- 真实付费 Provider 的价格和 Token 基线依赖部署方端点与密钥；仓库只保留协议测试、计量实现和不伪造数据的确定性基线。
