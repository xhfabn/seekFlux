# ADR-007：可归因交互事实与事务 Outbox

- 状态：Accepted
- 日期：2026-08-11

## 背景

发现、关键词搜索和 Agent 搜索已经能够返回真实内容，但此前曝光与主动行为只保存在浏览器本地，后端没有可供短期兴趣、推荐实验和离线评测复用的权威事实。客户端重试、Kafka 至少一次投递、乱序事件和撤回内容还会造成重复计数或错误归因。

## 决策

1. `POST /v1/interactions:batch` 是行为入站的唯一 HTTP 入口。每批必须携带 `Idempotency-Key` 和 `X-User-Id`，同一幂等键与相同请求体返回原回执；相同键配不同请求体返回 409。
2. 每个事件使用 UUID `eventId`。批次幂等保护 HTTP 重试，事件 ID 同时作为 Ingress、Outbox 与行为事实的稳定身份，保护跨批重复和 Kafka 重放。
3. 可公开分发的内容才接受事件。`CLICK`、`PLAY_START`、`LIKE`、`SAVE`、`PLAY_COMPLETE` 和 `NOT_INTERESTED` 必须能关联一条不晚于主动行为的已接受曝光，并完整匹配 `userId + requestId + traceId + contentId + position + surface`。
4. 批内先按事件时间处理曝光，再处理主动行为，使正常客户端批量回传不依赖数组顺序；只有未来曝光时返回 `EVENT_BEFORE_EXPOSURE`，没有对应曝光时返回 `ATTRIBUTION_NOT_FOUND`。
5. 批次回执、Ingress 事件和已接受事件的 Outbox 在同一个 PostgreSQL 事务中写入。非法事件保留为 `REJECTED` Ingress 诊断事实，但不产生 Outbox 或权威行为事实。
6. 每种行为使用独立的版本化 Kafka Topic。Worker 以 `eventId` 主键、`ON CONFLICT DO NOTHING` 写入 `interaction.facts`，因此至少一次投递和手工重放不会改变最终计数。
7. Interaction Context 只定义领域规则和 Port；JDBC 事务实现在 Persistence Adapter，HTTP 装配在 Online Server，Kafka 消费装配在 Worker Runner。业务接口继续使用 Spring MVC 同步 JSON，不引入 Reactor 返回类型。

## 后果

- 前端可以在 Feed、Search 和 Agent 三种 Surface 上使用后端返回的真实 request/trace 归因并批量回传，不能在前端伪造匹配结果或直接计算推荐特征。
- `interaction.facts` 成为 Step 9 实时特征的可信输入；当前阶段只建立事实，不提前实现 Flink 窗口、长期/短期兴趣合并或模型排序。
- 撤回后收到的事件会被拒绝；撤回前已经形成的历史事实不会被物理删除，后续分析需结合内容状态或数据治理策略解释。
- 当前客户端队列保存在浏览器本地，服务端可靠性从 HTTP 接收事务开始；需要跨设备离线同步时应另行设计客户端身份和传输协议。
