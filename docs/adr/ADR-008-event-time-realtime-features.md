# ADR-008：事件时间实时特征与在线快照

- 状态：Accepted
- 日期：2026-08-11

## 背景

Step 8 已把曝光与主动行为建立为可归因、幂等、可回放的版本化 Kafka 事件和 PostgreSQL 事实。Search/Feed 此前只使用显式画像与发布时间代理，无法反映用户刚发生的兴趣变化或内容当前热度；若直接在 Web 中计算，又会失去跨端一致性、重放能力和故障边界。

## 决策

1. 实时特征的唯一输入是已接受的 `interaction.*.v1` 事件。Interaction Outbox 会附带事件发生时已发布内容的 `content_tags`；前端队列、页面状态和对话文本不作为特征事实。
2. 共享 `feature-context` 冻结 `realtime-window-v1`：用户短期兴趣窗口 30 分钟、半衰期 10 分钟；内容热度窗口 5 分钟、半衰期 2 分钟；最大乱序 5 秒、允许迟到 30 秒、在线最大年龄 30 秒、Redis TTL 2 小时。行为权重由 `RealtimeFeaturePolicy` 统一定义。
3. 生产流任务位于 `pipelines/realtime-features`。它使用 Flink DataStream、KafkaSource、事件时间 Watermark、按事件 ID 的 TTL 去重、按用户/内容 Keyed State、Checkpoint，以及 `feature.snapshot.updated.v1` 与 `feature.interaction-late.v1` 两个输出 Topic。超过允许迟到边界的事件进入明确的补偿 Topic，不静默混入在线窗口。
4. 本地统一启动不额外引入 Flink 集群，而由 Worker Runner 内的 JDBC 参考投影器消费相同 Interaction Topic，复用同一策略并将中间事实、Watermark 和快照写入 PostgreSQL，再通过事务 Outbox 发布同一快照契约。该路径用于确定性开发、调试和端到端验收，不冒充生产 Flink 部署。
5. `OnlineFeatureSnapshotWriter` 把版本化快照写入 Redis Online Store。Online Server 只通过 `RealtimeFeatureUseCase` 读取；读取结果必须是 `FRESH`、`MISSING`、`STALE` 或 `UNAVAILABLE`，且快照携带 `computedAt` 和 `featureVersion`。
6. Search 将新鲜短期兴趣与内容热度作为现有 RRF 之后的可解释增量；Feed 将短期兴趣并入显式画像、保持画像强匹配，再把内容热度交给规则排序。特征缺失沿用既有基线；过期或读取失败时标记 `REALTIME_FEATURES` 降级并确定性回退，不能让主查询失败。
7. 快照 Topic 使用至少一次投递和稳定派生事件 ID。JDBC 参考投影器以源事件 ID 去重，Flink 以 Keyed TTL State 去重；Online Store 是可重建投影，不是权威事实来源。

## 后果

- 用户保存画像后产生的真实行为可以在秒级形成短期兴趣，并影响 Search/Feed；刷新页面或换前端实例不会丢失匹配逻辑。
- PostgreSQL 参考投影器保留调试事实与重放证据，生产 Flink Job 保留独立扩缩容和事件时间语义；两者不得使用同一消费组同时承担同一环境的权威投影。
- 当前 Flink Sink 为至少一次，端到端一致性依赖快照稳定身份与幂等覆盖，而不是宣称外部 Kafka Sink 已达到 exactly-once。
- 反作弊、长期/短期校准、补偿回算、独立 Flink 部署清单和模型排序属于后续范围；Step 9 只完成可版本化的实时特征底座及规则排序消费。

