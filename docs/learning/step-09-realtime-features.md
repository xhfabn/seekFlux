# Step 9：实时特征与短期兴趣

## 本阶段状态

- 状态：已完成
- 完成日期：2026-08-11
- 对应开发 Step：Step 9
- 对应 Agent Phase：Phase 4 可选深化
- 对应需求：用真实行为事件生成在线短期兴趣和内容热度，并让 Search/Feed 安全消费
- 对应 ADR 与契约：[ADR-008](../adr/ADR-008-event-time-realtime-features.md)、[OpenAPI](../../contracts/openapi/seekflux-v1.yaml)、[快照事件](../../contracts/events/realtime-feature-snapshot-v1.schema.json)、[Interaction Event](../../contracts/events/interaction-signal-v1.schema.json)

## 要解决的问题

显式画像描述用户长期选择，但不能表达刚刚收藏咖啡、连续完播露营或对某类内容负反馈。阶段 9 将 Step 8 的权威 Interaction 事件转换成带窗口、版本和新鲜度的在线特征；浏览器只展示结果，不在前端写死匹配和计分。

## 已实现链路

```text
Interaction API → PostgreSQL/Outbox → interaction.*.v1
                                      ├─ Flink 生产 Job：事件时间/Watermark/Keyed State
                                      └─ 本地 Worker：JDBC 参考投影与可重放事实
                                                    ↓
                                      feature.snapshot.updated.v1
                                                    ↓
                                      Worker → Redis Online Store
                                                    ↓
                                   Search / Feed → Web 用户画像信号
```

- `realtime-window-v1` 冻结 30 分钟短期兴趣、5 分钟内容热度、5 秒乱序、30 秒允许迟到、30 秒最大新鲜度和 2 小时在线 TTL。
- Interaction Outbox 补充发布时的内容标签；短期兴趣按行为权重和 10 分钟半衰期聚合，内容热度按 2 分钟半衰期聚合。
- Flink Job 消费七个 Interaction Topic，按事件 ID 去重，生成用户/内容 Keyed State；超界迟到进入 `feature.interaction-late.v1`。
- 本地 Worker 的 JDBC 参考投影器写入 `feature.realtime_events`、Watermark 和两类快照，再通过事务 Outbox 发布相同快照契约，便于本地调试和固定重放验收。
- Redis Adapter 以稳定 Key 保存短期兴趣和内容热度。Online Server 读取时统一返回 `FRESH/MISSING/STALE/UNAVAILABLE`，不会把过期值当作新鲜值。
- Search 的 Trace 和 Feed 页面返回特征状态、版本与计算时间；新鲜特征参与排序，缺失保持既有结果，过期或不可用时标记 `REALTIME_FEATURES` 并回退。
- 用户画像页能读取后端短期兴趣信号并刷新；发现页继续消费真实 Search/Feed 返回，不在前端计算推荐结果。

## 关键代码入口

| 入口 | 作用 | 建议阅读顺序 |
| --- | --- | --- |
| `contexts/feature-context/.../RealtimeFeaturePolicy.java` | 窗口、权重、衰减、Watermark 与版本定义 | 1 |
| `pipelines/realtime-features/.../RealtimeFeatureJob.java` | 生产 Flink Kafka 主拓扑 | 2 |
| `pipelines/realtime-features/.../EventTimeDeduplicator.java` | 事件 ID 去重和超界迟到 Side Output | 3 |
| `platform/persistence/.../JdbcRealtimeFeatureProjector.java` | 本地参考投影、可重放状态和快照 Outbox | 4 |
| `apps/worker-runner/.../OnlineFeatureSnapshotWriter.java` | Kafka 快照写入 Redis | 5 |
| `apps/online-server/.../RedisOnlineFeatureRepository.java` | 在线快照解码 | 6 |
| `contexts/search-context/.../SearchApplicationService.java` | Search 个性化与确定性回退 | 7 |
| `contexts/recommendation-context/.../RecommendationApplicationService.java` | Feed 短期兴趣合并、热度排序与回退 | 8 |
| `evals/run_realtime_feature_eval.py` | 固定事件序列全链路验收 | 9 |

## 失败与降级语义

- 重复 Interaction 事件：投影为 `DUPLICATE` 或由 Flink State 忽略，不重复累计。
- 5 秒乱序边界和额外 30 秒允许迟到内：事件仍进入窗口；更早事件记录为 `LATE_DROPPED`，生产 Job 同时写入迟到 Topic。
- Redis 没有快照：状态为 `MISSING`，Search/Feed 使用显式画像和既有规则，不视为系统故障。
- 快照超过 30 秒：状态为 `STALE`，值不进入排序，响应标记降级。
- Redis 内容损坏或读取异常：状态为 `UNAVAILABLE`，Search/Feed 继续返回既有候选并暴露 `REALTIME_FEATURES`。
- Flink/JDBC 到 Redis 链路中断：已有快照在新鲜度窗口内可读，超过窗口后自动走上述确定性回退。

## 验证命令

```bash
env JAVA_HOME=/Users/wujiawei/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home mvn test

cd apps/web
npm run lint
npm run build

cd ../..
./seekflux.sh up
python3 evals/run_realtime_feature_eval.py
```

Flink 的 `FlinkWindowExecutionTest` 会启动真实 MiniCluster，执行有界事件流并验证用户兴趣与内容热度输出，不用列表模拟替代 DataStream 执行。

## 完成证据

- Maven 全量测试通过，包含策略边界、读取新鲜度、Search/Feed 消费、Controller 契约和真实 Flink MiniCluster 窗口执行。
- Web ESLint 与生产构建通过。
- 固定结果 [`realtime-features-v1-baseline.json`](../../evals/results/realtime-features-v1-baseline.json) 的十项比较全部为 `true`：版本化新鲜快照、短期主题、内容热度、Search/Feed 消费、Kafka 重放稳定、允许乱序、超界迟到、过期与故障回退。
- 本地 `./seekflux.sh up` 已应用 Flyway V6、创建快照/迟到 Topic，并通过 Content、Worker、Online、Agent 和 Web 健康检查。

## 当前边界

- 本地统一脚本默认运行 JDBC 参考投影器；生产 Flink Job 已实现并通过 MiniCluster 测试，但独立 Flink 集群提交与监控清单仍需按部署环境配置。
- 当前热度是规则排序特征，不是训练模型；反作弊、长期/短期校准、回算与模型发布进入 Step 10。
- 固定评测证明链路语义和相对排序，不代表线上流量下的业务提升。

## 下一步

下一步是 Step 10：以版本化行为与实时特征构建训练样本、离线模型、模型注册和受控推荐实验。当前唯一进度与完成门槛以[学习路线首页](README.md)为准。

