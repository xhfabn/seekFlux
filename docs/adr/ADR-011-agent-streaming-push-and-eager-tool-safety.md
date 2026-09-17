# ADR-011：Agent 流式 Push 与 Eager Tool 安全边界

- 状态：Accepted
- 日期：2026-09-16

## 背景

AR-1～AR-6 已经建立取消、完整 Workspace 消息、Checkpoint、Tool journal、写副作用账本、Steer 和上下文治理。如果直接把 Provider token、Tool 参数分片和跨实例推送拼进现有同步接口，容易产生重复吐字、重连时重复执行、慢消费者拖垮主链，或在写 Tool 账本落盘前发出外部副作用。

## 决策

1. `ChatChunk` 是 Runtime 的 provider-neutral 流式协议，独立表达 content、reasoning、usage、finish reason 和按 index 组装的 Tool Call delta。空流、首 chunk 超时、输出前失败和输出后中断使用不同错误码。
2. `WorkspaceEvent` 继续是恢复事实，`PushEvent` 只是瞬态过程投影。`PushFrame` 在 Session 范围使用单调 sequence；实例内保留有界 history 和 subscriber queue，慢消费者溢出后断开，不反压 Agent 主链。
3. Redis 只承担跨实例 sequence 和 Pub/Sub relay，不成为新的事实源。frame 带 `sourceId`，接收方不再次广播，单个 listener 或 Redis 故障不改变 Runtime 终态。
4. SSE 新执行与重连明确分离：没有 `Last-Event-ID` 才启动执行；携带该 header 时只订阅既有 Session 流，绝不重复提交请求。历史已淘汰时先发 `REPLAY_GAP` control。
5. 流式重试最多一次，而且只允许发生在任何输出 chunk 对外可见之前。首个可见输出之后的断流直接失败，不允许重试造成重复文本或重复 Tool。
6. Eager Tool 只有在参数 JSON 完整、Tool 可见、Schema 校验/修复和执行策略通过后才可启动。当前仅 `READ_ONLY/IDEMPOTENT` 可 eager；稳定 Tool Call ID 用于与最终 Decision 精确匹配，不匹配的 Future 立即取消，结果仍按 call index 合并。
7. `MUTATING` Tool 不做模型流内 eager。它必须等 Assistant Decision 和 Tool journal 持久化，再执行 AR-4 的 `PREPARED → EXECUTING → external call` 账本协议。这里选择恢复安全优先于首包延迟。
8. Provider Adapter 传播 request/run/可选 W3C `traceparent`，支持受校验的请求级 model override，记录端点化 provider version 和 cached/reasoning token。动态多端点 client pool 在出现真实路由需求前不进入 Runtime；当前单端点复用一个有界宿主管理的 `HttpClient`。

## 后果

- 客户端可以消费文本、reasoning、Tool、Checkpoint、控制和终态事件，并通过 Session sequence 重连。
- history、订阅队列、Session 数量和 SSE 执行器都有硬上限；超过上限会明确断开或拒绝，而不是无限占用内存。
- Redis Pub/Sub 短暂不可用时当前请求仍可本地执行，但跨实例重连窗口会降级；恢复真相仍来自 PostgreSQL Workspace/Checkpoint，而不是补录 Push。
- Eager 只优化安全 Tool，写操作不会因流式化降低幂等、对账或恢复保证。
- WebSocket、Push 持久归档、多端点会话粘性和完整 OpenTelemetry exporter 不属于本决策，后续按产品需求单独演进。

## 关联

- [Agent Runtime 路线](../../platform/agent-runtime/ROADMAP.md)
- [Agent Runtime 说明](../agent-runtime.md)
- [Agent Push Frame v1](../../contracts/events/agent-push-frame-v1.schema.json)
- [OpenAPI](../../contracts/openapi/seekflux-v1.yaml)
