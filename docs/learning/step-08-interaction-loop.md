# Step 8：曝光与行为闭环

## 本阶段状态

- 状态：已完成
- 完成日期：2026-08-11
- 对应开发 Step：Step 8
- 对应 Agent Phase：Phase 4 可选深化
- 对应需求：发现、搜索和 Agent 候选产生可归因、幂等、可回放的行为事实
- 对应 ADR 与契约：[ADR-007](../adr/ADR-007-attributed-interaction-facts.md)、[OpenAPI](../../contracts/openapi/seekflux-v1.yaml)、[Interaction Event Schema](../../contracts/events/interaction-signal-v1.schema.json)

## 要解决的问题

用户在发现页看到内容并点赞后，后端必须知道这次行为来自哪次请求、哪条 Trace、哪个位置和哪个 Surface；网络重试、同一事件跨批上报或 Kafka 重放都不能重复计数。撤回内容、无曝光主动行为和行为早于曝光必须得到稳定的拒绝原因。

本阶段不计算短期兴趣、不运行 Flink 窗口，也不训练排序模型。它只把后续计算所需的输入建立成可信事实。

## 架构位置

```text
Web Feed / Search / Agent
  -- POST /v1/interactions:batch --> Online Server
                                      |
                                      v
                              Interaction Use Case
                                      |
                                      v  同一 JDBC 事务
                         batches + ingress_events + outbox
                                      |
                                      v
                              Kafka versioned topics
                                      |
                                      v
                             InteractionFactWorker
                                      |
                                      v
                              interaction.facts
```

Interaction Context 只包含领域对象、输入 Port、输出 Port 与应用服务。HTTP Adapter 位于 Online Server，JDBC Adapter 位于 Persistence，Kafka Consumer 位于 Worker Runner；Context 的领域代码不依赖 Spring、Kafka 或数据库 API。

## 完成了什么

- 冻结 `EXPOSURE`、`CLICK`、`PLAY_START`、`LIKE`、`SAVE`、`PLAY_COMPLETE`、`NOT_INTERESTED` 七类行为，以及 `FEED`、`SEARCH`、`AGENT` 三类 Surface。
- 新增批量 Interaction API：1～100 个事件，校验用户、幂等键、UUID、归因字段、位置和未来时间偏移；首次接收返回 202，幂等重放返回 200。
- 同一幂等键、同一请求体返回保存的原始批次回执；同一键、不同请求体返回 `INTERACTION_IDEMPOTENCY_CONFLICT`。
- 主动行为必须关联已接受且时间不晚于它的曝光；批内先处理曝光再处理主动行为，不依赖客户端数组排列。
- 未找到内容返回 `CONTENT_NOT_FOUND`，内容未发布或已撤回返回 `CONTENT_NOT_PUBLISHED`，无曝光返回 `ATTRIBUTION_NOT_FOUND`，只有未来曝光返回 `EVENT_BEFORE_EXPOSURE`。
- 已接受事件与 Outbox 原子写入；Kafka Worker 以事件 ID 幂等建立 `interaction.facts`，重复投递不增加事实数。
- 发现页用 Search/Feed/Agent 后端真实返回的 request/trace/position 构造曝光和主动行为；用户画像页展示队列并调用真实 API 回传，不在前端计算后端匹配逻辑。
- 本地启动脚本创建七个 Interaction Topic；固定 Eval 覆盖 HTTP 幂等、事件去重、归因、乱序、撤回内容和 Kafka 重放。

## 与 Agent 主线的关系

Agent Phase 0～3 和 Direct Search 仍保持独立可用。Agent 搜索返回的候选现在可以带 `AGENT` Surface 进入同一行为链路，但 Interaction Context 不依赖 Agent Runtime，也不改变 Agent 的有限步执行、Tool 或回退语义。

行为事实是 Phase 4 推荐深化的输入，不是 Agent 主线成立的前提。下一步实时特征必须消费这些权威事实，不能从前端本地状态或 Agent 对话文本猜测兴趣。

## 核心流程

1. Search、Feed 或 Agent 返回真实候选及 request/trace 信息，Web 为可见卡片记录曝光。
2. 点赞等主动行为复用同一条候选的 user/request/trace/content/position/surface。
3. Web 批量调用 Interaction API；应用服务校验请求并计算规范化请求哈希。
4. JDBC Adapter 锁定幂等批次，校验内容状态与曝光归因，将回执、Ingress 和 Outbox 原子提交。
5. Outbox Relay 投递对应版本化 Topic，Worker 将事件幂等写入行为事实表。
6. HTTP 重试返回原回执；跨批重复事件返回 `DUPLICATE`；Kafka 重放因事实主键冲突成为无操作。

失败路径：主动行为没有匹配曝光时，批次仍正常返回 202，但该事件回执为 `REJECTED/ATTRIBUTION_NOT_FOUND`，Ingress 保留原因，且不产生 Outbox 和行为事实。

## 关键代码入口

| 入口 | 作用 | 建议阅读顺序 |
| --- | --- | --- |
| `contexts/interaction-context/src/main/java/io/seekflux/interaction/domain/InteractionSignal.java` | 行为与归因值对象 | 1 |
| `contexts/interaction-context/src/main/java/io/seekflux/interaction/application/InteractionApplicationService.java` | 批次校验、哈希与用例入口 | 2 |
| `platform/persistence/src/main/java/io/seekflux/platform/persistence/interaction/JdbcInteractionRepository.java` | 幂等、归因、事务 Ingress/Outbox | 3 |
| `apps/online-server/src/main/java/io/seekflux/apps/onlineserver/api/InteractionController.java` | Spring MVC 批量 API | 4 |
| `apps/worker-runner/src/main/java/io/seekflux/apps/workerrunner/interaction/InteractionFactWorker.java` | Kafka 幂等事实消费 | 5 |
| `apps/web/app/SeekFluxApp.tsx` | 三种 Surface 的真实曝光和主动行为回传 | 6 |
| `evals/run_interaction_loop_eval.py` | 真实全链路固定验收 | 7 |

## 设计取舍

项目选择 PostgreSQL 事务 Ingress + Outbox，而不是 HTTP Controller 直接发送 Kafka。这样 API 成功意味着事件已经可靠落地，进程在数据库提交后、Kafka 发送前崩溃也能恢复。Kafka 保持至少一次投递，最终事实用稳定事件 ID 去重，而不是依赖消费者“恰好一次”的环境假设。

主动行为采用严格完整归因，而不是只按 `contentId` 找最近曝光，避免把不同请求、位置或 Surface 混在一起。具体长期决策见 [ADR-007](../adr/ADR-007-attributed-interaction-facts.md)。

## 如何验证

```bash
./seekflux.sh up

env JAVA_HOME=/Users/wujiawei/Library/Java/JavaVirtualMachines/ms-21.0.11/Contents/Home mvn test -q

cd apps/web
npm run lint
npm run build

cd ../..
python3 evals/run_interaction_loop_eval.py
```

固定数据集为 [`interaction-loop-v1.json`](../../evals/datasets/interaction-loop-v1.json)，固定结果为 [`interaction-loop-v1-baseline.json`](../../evals/results/interaction-loop-v1-baseline.json)。Runner 需要本地 Content、Online、Worker、PostgreSQL 和 Kafka 全部在线。

真实页面验收：使用 `demo-user` 在发现页搜索“露营”，产生 14 次曝光并点赞首条内容；用户画像页回传显示“接收 15，重复 0，拒绝 0”。数据库中点赞与曝光的 `request_id + trace_id + content_id + position + surface` 完整一致。

## 完成证据

- Maven 全量测试在 JDK 21 下通过，包含 Interaction 应用服务和 Controller 契约测试。
- Web ESLint 与生产构建通过。
- 固定 Eval 的九项比较全部为 `true`：新批接收、同键重放、变体冲突、跨批事件去重、曝光行为关联、非法归因拒绝、乱序拒绝、撤回内容拒绝、Kafka 重放幂等。
- `./seekflux.sh up` 完成 Flyway V5、Topic 创建和全部服务健康检查。
- 真实浏览器与数据库联合验收形成 15 条 `demo-user` 行为事实，其中 LIKE 与对应 EXPOSURE 归因一致。

## 本阶段可以学到什么

- HTTP 幂等键解决“同一请求是否重复”，事件 ID 解决“同一事实是否重复”，两层身份不能互相替代。
- Outbox 把数据库事实与消息发布之间的双写问题转成可重试状态机。
- 至少一次投递需要消费者幂等；Kafka 重放是正常运维动作，不应改变权威计数。
- 曝光归因是推荐训练和效果评估的前提，只记录点赞而没有展示上下文会产生严重选择偏差。

## 练习与自检问题

1. 读代码：沿着 LIKE 从 `InteractionController` 追到 `interaction.facts`，指出每一层使用的幂等身份。
2. 小改动：为 `PLAY_COMPLETE` 增加一条 Controller 测试，验证它没有曝光时被拒绝。
3. 设计题：如果客户端离线七天后回传，应该如何区分迟到但有效、时钟错误和超出业务保留期？

## 常见问题与排查

- Eval 等待事实超时：先用 `./seekflux.sh status` 检查 Worker 和 Kafka，再确认 `deploy/local/stack.sh` 已创建全部七个 Interaction Topic；查看 `.local/apps/logs/worker.log` 与 Kafka 日志。
- API 返回 409：同一个 `Idempotency-Key` 被用于不同请求体，应重用原请求或生成新的批次键，不能静默接受。
- 主动行为被拒绝：核对曝光和主动行为的 user/request/trace/content/position/surface 是否逐项一致，以及曝光时间是否不晚于行为。
- 页面有队列但事实表为空：先看回传回执的 accepted/rejected 数，再检查 Outbox 状态和 Worker 消费组，不能仅凭前端队列判断服务端成功。

## 下一步

当时的下一步是 Step 9：以 `interaction.facts`/版本化 Kafka 事件为输入，建立窗口化实时特征和短期兴趣，并证明迟到、重放、特征新鲜度与在线读取语义。当前唯一状态和验收门槛以[学习路线首页](README.md)为准。
