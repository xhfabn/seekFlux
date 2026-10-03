# Agent persistence v2

本契约定义持久编码，不替换内存 `RuntimeCheckpoint` / `ToolCallJournalEntry` DTO。
Core 不提供 JDBC 依赖；其他宿主必须自行实现同样的原子提交、fencing 和幂等要求。

## 权威事实与编码

所有 Workspace 事实拥有 session 内单调 position、不可变 event ID、schemaVersion、时间和
request/turn/call 关联。相同 ID、相同内容可重试；相同 ID、不同内容必须失败，不能覆盖旧事实。

| 表/事件 | 内容与边界 |
| --- | --- |
| `ASSISTANT_MESSAGE` | 完整正文、reasoning/replay 标记、稳定 Tool Call ID/index 和参数；与 DECIDED journal 原子提交 |
| `TOOL_RESULT_MESSAGE` | 完整 raw/model/display/structured 视图、状态、错误、Trace；与 terminal journal 原子提交；每个 call 最多一条 |
| ToolResult 的 `observation` | 额外参数、effect 结果元数据、外部 receipt、ToolGroup switch 等；result.output 引用本事件 rawContents，不再复制输出 |
| `TOOL_DISPATCHED` | call/attempt/fence；在 journal EXECUTING 事务中追加，外部请求在提交后发生 |
| `EXECUTION_METADATA` | 冻结 definition/features，按内容身份去重 |
| `EXECUTION_STEP` | 稳定 execution Trace index 和完整单条 StepTrace，只追加新增条目 |
| `EXECUTION_PROGRESS` | checkpointId、boundary、attempt、nextStep、toolCallCount、剩余预算、fence、usage、指纹、metadata/terminal 引用 |
| `EXECUTION_TERMINAL` | 完整最终结果/等待状态与 Trace 标量；messages、trace.steps、trace.definition 由事实引用解析 |
| `COMPACTION_COMMITTED` | summaryId、fromExclusive、inclusiveCutoff、strategyVersion；正文保存在同事务的 context_compactions |

`runtime_checkpoints.schema_version=2` 的 payload 只包含：

```json
{"statePosition": 137, "metadataPosition": 3, "terminalPosition": 136}
```

terminalPosition 只在终态/挂起时出现。message_cutoff 必须等于 statePosition，指向同一 Session
已提交的高水位。PRE_TURN 的历史重建只含 step < nextStep 的消息/observations；当前 step
从 journal 和相关消息恢复，避免重复注入。POST/terminal 包含截至 cursor 的相应事实。

Journal v2 payload 保留 arguments/argumentsRepaired、assistantMessageId，以及终态时的
observationToolCallId。数据库列继续保留身份、effect、状态、参数摘要和 fencing。
旧 v1 内联 payload 仍能读取；不能删除其内容后再尝试从尚未追加的消息恢复。

冻结 definition 新增 `toolDefinitions`：名称、说明与完整参数 Schema（含参数说明）。该映射进入
`EXECUTION_METADATA`，不是每个 Checkpoint 内联副本；老字段缺失仍可读取。恢复/版本检查见
[工具定义契约](agent-tool-definition-v1.md)。本轮不新增持久表或更改 V16。

## Session 状态快照

V16 新增 `session_snapshots`，主键为 `(session_id,event_position)`。payload 保存
`AgentSessionSnapshot`：schemaVersion、Session/Agent 身份、position、stateVersion、workspaceState、
status、capabilities、pendingToolCallIds、retainedEventPositions。

retainedEventPositions 是待处理 queue、pending wait、最新输入和必要 promotion 的引用；不复制
正文。恢复先加载基线，再按 position 重放 tail；历史正文按消息/摘要引用读取，不参与基线以前
的状态 reducer。公开查询历史 wait 不依赖热窗口仍保留该 WaitSuspended 事件。

默认 `seekflux.agent.session.snapshot-interval-events=100`，必须是正整数；挂起/终态强制保存。
快照和事件范围必须来自一致读视图；后台摘要提交不能造成旧 cutoff + 新 summary 的撕裂组装。

## 安全与验收

- 完整消息提交后才能作为已确认事实推进本地执行投影；过程 Push 不是恢复依据。
- 写调用仍必须使用独立 SideEffect Ledger/外部 reconciliation，不能从出队或 snapshot 推断成功。
- Outcome 本身有稳定 ID/content 检查，消息/终态/Outbox 保持事务一致；不删除原始消息和摘要。
- 未发布 RC2 的 Core 新增兼容 overload；旧宿主采用默认方法仍保留其旧持久语义，升级为本契约
  必须显式实现新 `recordToolResult(call,message,fence,time)` 原子提交接口及增量决策提交。
- 不保证未提交模型轮不重算，不持久保存 token delta，不实现快照 GC/归档或完整 Redis 投影缓存。

长期决定见 [ADR-017](../../docs/adr/ADR-017-agent-event-facts-and-reference-snapshots.md)。
