# Agent Observation v1

## 范围

Agent Runtime 的可选执行观测投影，独立于 AgentRunEvent、Session 消息、Checkpoint 与 Tool journal。默认 NOOP，不改变旧持久化或推送协议。

## Envelope

公共模型为 AgentObservationEvent，schemaVersion=1。

| 字段 | 含义 |
| --- | --- |
| eventId | 每条事件唯一；异步导出重试复用 |
| identity.agentRunId/requestId/sessionId/turnId | 所属运行与请求 |
| identity.observationId/parentObservationId | 当前操作及父操作 |
| identity.toolCallId | 可选的逻辑工具调用身份 |
| identity.attemptId | 当前观测 Scope 的尝试身份，不等同于副作用幂等键 |
| kind | RUN、PLAN、CONTEXT、MODEL、TOOL、TOOL_PREPARATION、RECONCILIATION；RUNTIME_EVENT 为保留种类 |
| phase | STARTED、EVENT、COMPLETED、FAILED、CANCELLED |
| name | 操作名称，例如 llm、llm.shadow、工具名 |
| occurredAt | UTC Instant |
| durationNanos | 从当前 Scope 开始计算的单调耗时 |
| outcome | 业务或阶段结果，如 SUCCEEDED、FAILED、WAITING、APPROVAL_REQUIRED |
| data | 有界、不可变、已脱敏快照；不能依赖原 Java 对象引用 |

## 生命周期

每个 Scope 在内存内只发一次终态；STARTED/EVENT/终态共用身份。这个约束不代表传输 exactly-once，队列丢弃可造成不完整轨迹，重复导出需按 eventId 去重。

COMPLETED 表示观察操作正常返回，不保证业务成功；工具返回失败仍可为 COMPLETED/FAILED outcome。RUN 的 outcome 反映 Runtime 终态，WAITING 也可正常返回。异常使用 FAILED；直接 AgentCancellationException 使用 CANCELLED。

Runtime 超时停止等待与实际工具结束是不同事件。TOOL_WAIT_ABANDONED/EAGER_CANCEL_REQUESTED 不能解释为远端操作已经取消。实际调用可以晚于 RUN 结束完成。

恢复直接返回旧终态时，以新观测 RUN 记录恢复调用，data.resultRunId 指向持久化结果的原 agentRunId；不得伪造模型或工具调用。

## 内容与格式

input/output/errorMessage 默认不采集。启用后按 maxCharacters/maxNodes/maxDepth 截断，使用 [truncated] 或 _truncated 标识；null 转为 [null]，不支持的对象转为类型标识，不能将快照当作无损请求重放协议。

默认字段名脱敏不覆盖自然语言里的敏感信息。Processor 在入队前可筛选或修改 data，其返回对象不能改变身份、phase、kind、name、时间和 outcome。

Exporter 负责外部编码与目标协议。Core 不承诺 Langfuse/OTel 原生兼容，也不保存访问目标平台的凭据。多目标或部分批次失败可能导致重投。

## 模型与执行边界

模型事件表示 LlmClient 调用，非 HTTP 请求；Provider 内部修复/重试需要 Adapter 自行细化观测。流式不逐 token 上报，保留 chunkCount、首输出耗时及最后观测用量，失败时不保证保存全部部分文本。measured=false 与真实零用量不同。

默认 Loop 自动装饰模型和上下文；自定义 Planner/Loop 使用显式装饰器。工具通过 Runtime 的执行入口采集，不要求 Tool 主动上报。自定义执行器隐藏的原始错误无法由外层恢复。

## 失败语义

采集/处理/observer 的 RuntimeException 增加 captureFailureCount，不改变业务异常或结果。用户回调必须非阻塞；JVM 致命错误不承诺隔离。默认异步实现支持有限重试、有界队列、限时关闭，未实现持久 outbox。

完整用法与参数见 [观测指南](../../platform/agent-runtime/OBSERVABILITY.md)。验收状态见 [检查清单](../../evals/agent-observation-acceptance.md)。
