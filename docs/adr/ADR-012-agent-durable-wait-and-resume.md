# ADR-012：Agent 持久等待、幂等决议与恢复边界

- 状态：Accepted
- 日期：2026-09-17

## 背景

AR-3 已能从 Checkpoint 和 Tool journal 恢复安全执行，AR-5 已允许等待态继续接收排队消息，
但 `NEED_APPROVAL` 仍然只是失败关闭；异步 Tool、Waitpoint 和人工审批也没有可以跨进程、跨
Pod 重放的 pending 事实。仅把 Future 留在内存会在实例退出后丢失等待，直接重放 Tool 又可能
重复外部副作用。

## 决策

1. Runtime 使用独立终态 `WAITING` 表达“当前 segment 已安全挂起，但 Session 尚未完成”。
   Session 投影仍为 `SUSPENDED`，等待原因由类型化 `WaitState` 区分 `HITL`、`ASYNC_TASK`、
   `WAITPOINT`、`HANDOFF` 和 `CHILD_AGENT`。
2. 挂起必须在一个事务内保存 `SUSPENDED` Checkpoint、状态为 `WAITING` 的 Tool journal 和
   `agent.runtime_waits` pending 行。随后 Workspace 追加 Assistant 与 `WAIT_SUSPENDED`；等待
   不是终态 Outbox 事实。
3. 所有外部结论统一为 `WaitResolution`，通过 execution authority、fencing token 和行锁执行
   first-writer-wins。`resolutionId` 是幂等键；服务端接收时间不参与同一决议判断，重复同一内容
   返回 duplicate，改变 outcome/output/actor 的重试返回 conflict。
4. 合法决议受等待类型约束：HITL 只接受 approve/deny 或关闭类结论；Async、Waitpoint、
   Handoff、Child 只接受 completed 或关闭类结论。禁止用 `COMPLETED` 绕过审批，也禁止用
   `APPROVED` 重派异步任务。
5. `APPROVED` 把原 journal 恢复为 `DECIDED`，以原 Tool Call ID 执行一次；`COMPLETED` 把
   回调内容补为 ToolResult，不重新派发 Tool；deny、timeout、cancel、failure 形成稳定 ToolResult
   后沿既有恢复主链收敛。人工决议记录由服务端认证头得到的 `resolvedBy`。
6. Agent 执行预算在挂起期间冻结。HITL 策略和 Tool `WaitRequest` 各自声明独立等待期限，不能
   被本轮剩余的数秒模型预算截断；有界后台扫描器只提交确定性 timeout resolution，回调/超时
   竞态仍由数据库仲裁。
7. pending 状态丢失不能一律吞掉。HITL、Waitpoint、Handoff fail-fast；Async 与 Child 可从仍在的
   suspended Checkpoint 重建 pending，再接纳可验证的 callback result。
8. 普通用户请求在 typed wait 存在时只能 `QUEUE`；恢复只能走内部 `Router.resume`。Session
   cancel 会把持久等待解析为 `CANCELLED`，避免只写一个没有运行 token 消费的取消信号。
9. `WorkspaceEvent` 继续是会话历史事实，`runtime_waits` 是竞态仲裁与审计事实，`PushEvent` 只
   是过程投影。等待恢复后才消费 AR-5 已积压的队列消息。
10. Handoff/Child 的通用 SPI、预算/深度/身份传播和结果回填协议可以先存在，但没有真实产品
    launcher、持久父子关系和 fork promotion 前不得宣称 AR-8C 完成；当前 Fork 明确返回
    `FORK_PROMOTION_UNSUPPORTED`。

## 后果

- 审批、Webhook 回调、等待条件与超时都复用同一条“原子决议 → Checkpoint 恢复 → ToolResult
  补偿 → Loop 继续”路径。
- 已经挂起的 Session 可跨进程恢复，重复回调和 timeout/callback 竞态不会重复执行 Tool。
- resolved wait 行在恢复状态清理后仍保留，支持重复请求幂等与审计；Checkpoint/journal 外键清空
  后置空，不删除决议事实。
- 当前 Search 产品没有真实子 Agent/Fork 用例。父子取消级联、fork/promote 和源目标 Session
  关系仍为后置能力，不能从已有接口骨架推断为已交付。

## 关联

- [Agent Runtime 路线](../../platform/agent-runtime/ROADMAP.md)
- [Agent Runtime 说明](../agent-runtime.md)
- [Agent Wait Lifecycle v1](../../contracts/events/agent-wait-lifecycle-v1.schema.json)
- [OpenAPI](../../contracts/openapi/seekflux-v1.yaml)
