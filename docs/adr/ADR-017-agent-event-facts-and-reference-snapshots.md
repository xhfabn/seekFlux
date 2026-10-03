# ADR-017：Agent 增量事实与引用式状态快照

- 状态：已接受
- 日期：2026-10-03
- 范围：Runtime Core 的恢复/上下文 SPI，宿主 PostgreSQL Adapter；不改变已发布 RC1。

## 背景

原 AR-2 在 segment Outcome 中批量保存 Assistant/ToolResult，AR-3 使用自包含 Checkpoint
保存进行中的消息、observations 和完整 Step Trace。该协议能够从安全点恢复，但重复序列化和
覆盖增长中的 payload；同一 execution 的后续模型输入还依赖 observations，而不是完整消息。

## 决策

1. PostgreSQL `workspace_events` 是完整消息和执行事实的权威来源。Assistant 与 DECIDED
   journal、ToolResult 与 terminal journal 在 Session 行锁/fencing 事务中一起提交；完整终态
   消息和结果与恢复游标一起提交。幂等 ID 的不同内容是冲突，不得静默覆盖。
2. Checkpoint v2 仅持有 immutable state/metadata/terminal 事实的 position 引用。
   `EXECUTION_PROGRESS` 保存边界、预算、计数、usage、指纹及元数据引用；冻结定义/features
   按内容身份追加，Step Trace 只追加新增条目。Terminal 事实不再次内嵌 messages/trace.steps。
   Journal v2 引用 Assistant message ID 与 ToolResult call ID，不复制正文和输出。
3. `RuntimeCheckpoint` 仍是内存恢复 DTO，允许携带已解析消息/observations/steps。
   持久编码不等同于内存 DTO。v1 自包含编码仍可读取，继续执行时追加缺失事实再保存 v2 游标。
4. `session_snapshots` 保存 Session 聚合状态、能力状态、pending call ID 和队列/等待/当前输入的
  事件 position 引用，不保存消息正文。默认每 100 个事件创建一份，挂起和终态强制创建；
   `seekflux.agent.session.snapshot-interval-events` 可配置为 10 等正整数。
   恢复只对 snapshot.position 之后的事件执行状态 reducer；旧消息与 pending 输入按引用读取。
5. 模型上下文包含 execution 内完整 Assistant/ToolResult。恢复时按 message ID 去除与 Session
   历史重叠的消息，不再同时注入这些消息和 observation 文本。历史加载只读取最新摘要 cutoff
   之后的正文；当前 execution 消息仍受完整 turn/Tool 配对保护。
6. 摘要正文继续保存在追加式 `context_compactions`，同事务追加 `COMPACTION_COMMITTED`
   引用事件后才返回新摘要；原始消息不删除、不改写。恢复快照 position 与压缩 cutoff 分责。
7. Queue 仍是 `QUEUED_USER_MESSAGE` / `USER_MESSAGE` 的持久逻辑队列。内存批次是临时投影；
   升格后未执行的 request 由既有接管协议恢复。历史 wait 身份可直接从持久 wait store 查询。
8. Redis authority/cancellation 与业务结果缓存保持现状，不新增另一个不可重建的事实源。
   本轮没有实现共享 Runtime Redis 投影缓存；该优化不影响正确性。

## 失败与事务边界

- 所有权判断和事实追加在同一个 Session 行锁事务下线性化；旧 fence 无法追加。
- 模型已提交 DECIDED 时不重新决策；已经提交 ToolResult 时不重新执行该工具。
- 并行调用按稳定 index 收集，收集到单个结果后立即提交，不等整个批次完成。
  已提交结果不因之后的取消而改写为 CANCELLED；未提交/未知写操作仍遵循副作用账本对账。
- 模型返回但尚未提交任何决策/终态事实时崩溃，允许重算该模型轮；不承诺 token 级续传。
- Outcome 保留独立事务 Outbox 与清理恢复游标/journal；原始执行事实、摘要和 Session 快照保留。
- 快照是可重建加速数据，不代替外部副作用账本，也不决定远端写操作是否已经发生。

## 后果与边界

消除大正文的每步 Checkpoint 覆盖写，新增事实写入量随消息/Trace 增长；少量运行计数、usage 和
指纹仍会在安全点写入。当前不做跨事件正文去重、token delta 落库、历史归档、快照 GC 或当前
protected turn 的激进压缩；超限仍遵循已有 ContextOverflow/OutputGuard 边界。

持久结构见 [v2 契约](../../contracts/runtime/agent-persistence-v2.md)，真实 PostgreSQL 验收入口为
`AgentEventPersistenceIntegrationTest`，实现与验证记录见 [Step 07](../learning/step-07-agent-reliability-platform.md)。
