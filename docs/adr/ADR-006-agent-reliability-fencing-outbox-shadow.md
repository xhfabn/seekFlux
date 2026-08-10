# ADR-006：Agent 多实例可靠性、事务事实与 Shadow 治理

- 状态：Accepted
- 日期：2026-08-10

## 背景

Step 6 的 Runtime 能处理复杂 Query 和多轮约束，但 Redis 租约本身不能阻止已经失去租约的旧实例晚到提交；进程内取消和 Shadow 开关也不能跨实例生效。Agent 终态如果只写 Session 而不产生可靠事件，评测和审计消费者无法在故障后重放。

参考《Ark-Leto 框架内核 与 Agentspark 主链路 原理详解》中的执行权、强恢复、事件分层、取消顺序和有限并发原则，本阶段补齐当前 Search Agent 在多实例部署下必须满足的不变量。项目仍是自研 Runtime，不依赖 Ark-Leto 二进制或源码。

## 决策

1. Redis 获取执行权时原子递增每个 Session 永不过期的 fencing 计数器，租约值保存 `owner|fencingToken`；续租和释放都使用 owner-CAS Lua。
2. fencing 覆盖从 Ingress 到终态提交的整个持权区间。PostgreSQL Session 保存 `active_fencing_token`，状态补丁和终态提交都拒绝旧 token；Loop 返回后必须再次续租成功才能提交结果。
3. 新 owner 获权后从 PostgreSQL `workspace_events` 强一致重放 Session。若相同 `requestId` 已提交但 Session 仍为 `EXECUTING`，更高 fencing token 可以认领该轮并创建新的 Run attempt；普通重复请求仍返回 409。
4. 取消信号写入 Redis 并带发生时间，运行实例按有界间隔轮询；任务只响应晚于本次启动时间的信号。优雅停机先停止接收新执行，广播取消，等待受控宽限期，再关闭调度器。清理顺序保持“移除本机 token → owner-CAS 释放租约”。
5. Session 终态、`WorkspaceEvent` 和 Agent Outbox 在同一 PostgreSQL 事务中提交。Outbox 事件 ID 由 Session 与事件位置确定性生成；Kafka 审计消费者以 `eventId` 主键幂等写入。
6. 模型与 Tool 使用独立 Semaphore Bulkhead，饱和时快速返回稳定错误；模型、Tool、执行权丢失和跨实例取消均有固定故障测试。Tool 声明 `READ_ONLY | IDEMPOTENT | MUTATING` 效果类型，当前 Search Tools 均为 `READ_ONLY`，Tool Call ID 由请求和规范化参数确定性生成。
7. OpenAI-compatible Adapter 解析 Provider usage，并按配置价格计算微美元；Trace 和 Micrometer 指标关联 Agent、Prompt、Provider 与 Tool Schema 版本。默认确定性 Provider 不报告 Token，不伪造成本。
8. Shadow 使用与主链隔离的有界执行器，候选异常、超时或队列饱和都不得改变主结果。采样开关保存在 Redis，管理 API 的关闭对其他实例下一次请求生效；对比结果异步写入 PostgreSQL。
9. macOS 本地中间件通过 launchd 托管 Kafka、Elasticsearch 和 MinIO，避免启动命令退出后子进程被回收，保证固定评测可重复运行。

## Ark-Leto 反向核对

| 参考不变量/能力 | SeekFlux 状态 | 说明 |
| --- | --- | --- |
| `Router → FeaturePipeline → SessionExecutor → AgentLoop` | 已实现 | Runtime Core 保持业务无关，Search 语义在 Context/Adapter |
| 先 acquire，再提交 UserMessage | 已实现 | busy 不产生 Ingress 事实 |
| owner-CAS 续租/释放与 fencing 全区间保护 | 已实现 | Redis Lua + PostgreSQL `active_fencing_token` + 提交前最终校验 |
| 接管前强一致恢复 | 已实现 | 每轮从 PostgreSQL 追加事件重放；崩溃中的相同请求可由高 token 认领 |
| 本地 token 先移除，再释放执行权 | 已实现 | `SessionExecutor.finally` 固定清理顺序 |
| Workspace/Run/Push 三类事件分责 | 部分实现 | Workspace 与 Run 已持久化；项目按既定同步 JSON 边界不提供流式 Push |
| 分布式 cancel | 已实现 | Redis 信号按运行起始时间过滤，跨实例测试通过 |
| steer 先入队、再 cancel | 未实现 | 当前没有 QueuedUserMessage/插话 API，不能把 `steer=true` 误称为完整 steer |
| Tool Schema、动态工具、并行调用、部分成功 | 已实现 | 共同 Deadline、稳定调用 ID、候选复用和 Bulkhead |
| Checkpoint 精确恢复 pending Tool Call | 未实现 | 当前接管从 Session 事实重跑该轮；仅对现有只读 Search Tool 安全 |
| Mutating Tool 副作用账本 | 未实现 | 已有 Effect 元数据和稳定 ID，但尚无持久化幂等回执；引入写 Tool 前必须补齐 |
| 上下文分层压缩与超长重试 | 未实现 | 当前上下文规模有界，尚无摘要/裁剪/Provider 413 修复 |
| OutputGuard 自动修复、eager dispatch | 未实现 | 当前只做结构化 Decision 解析和一次 Tool 参数修复 |
| HITL、异步等待点、Handoff、子 Agent | 未实现 | 不是当前 Search Agent 主链需要，后续按具体产品场景决定 |
| Skill/ToolGroup、MCP、Chained/Graph Agent | 未实现 | 当前请求级动态 Tool 集已足够；不会为框架完整度提前引入 |
| SSE/WebSocket 与跨 Pod Push 中继 | 未实现 | 用户已选择普通同步接口；若以后需要长任务进度再独立设计 |

因此，“阶段 7 完成”只表示多实例可靠性与平台治理切片完成，不表示参考文档列举的所有可选 Agent 形态都已经实现。

## 后果

- 旧 owner 即使继续运行也不能污染 Session 终态或 Outbox；Redis 故障时续租失败，主链按失主处理而不是冒险提交。
- Session 是唯一权威业务状态，Run attempt 可以保留失主和接管诊断记录；审计消费者可从 Outbox 重放。
- Shadow 可跨实例快速关闭且不增加主链失败率，但当前管理 API 仍是内部接口，生产部署前必须接入平台鉴权与变更审计。
- 现有 Search Tool 全部只读，因此接管重跑不会产生外部写副作用；未来新增发布、支付或通知类 Tool 前，Checkpoint/副作用账本成为硬门槛。
- 真实付费 Provider 的价格和 Token 基线依赖部署方端点与密钥；仓库只保留协议测试、计量实现和不伪造数据的确定性基线。
