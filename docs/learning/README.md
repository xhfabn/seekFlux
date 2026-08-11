# SeekFlux 学习路线与实现日志

这组文档是项目唯一的进度入口和按序实现日志。目标架构以 [`SeekFlux.md`](../../SeekFlux.md) 为准，依赖规则以[模块边界](../module-boundaries.md)为准；这里不再复制架构总览，只负责回答四个执行问题：

1. 当前已经由代码和测试证明了什么；
2. 现在处于哪个开发 Step，对应 Agent 路线的哪个 Phase；
3. 下一步只做什么，完成门槛是什么；
4. 完成后怎样通过代码、测试、评测和练习真正掌握它。

## 当前进度

> **当前处于：Step 8 已完成，下一步进入 Step 9「实时特征与短期兴趣」。**

截至 2026-08-11，已经跑通以下真实链路：

- 内容登记 → PostgreSQL/Outbox → Kafka → Worker 生成画像并发布 → Elasticsearch 建索引；
- 用户画像保存到 Redis → Search/Feed 使用真实后端数据；
- 关键词搜索，以及热门、兴趣、相似内容召回、画像标签强匹配、规则排序、Cursor 和单路降级；
- Direct Search 的 BM25/kNN 双路召回、RRF 融合、结构化约束、共同 Deadline、单路降级和版本化 Search Trace；
- 自研 Agent Runtime 主链路 `Router → FeaturePipeline → SessionExecutor → AgentLoop`，以及有限步、共同 Deadline、Tool Schema、稳定终态和版本冻结；
- PostgreSQL Session 追加事件与独立运行事件、Redis 执行权/热投影、重复请求保护和同步取消入口；
- 两个配置化 AgentDef、Search Tool Adapter、追问、Agent → Direct Fallback、Agent/Search 双 Trace 和 Direct/Agent 对照 Eval；
- 简单 Query 直达 Search、复杂 Query 进入 Agent 的 AUTO Router，结构化 SearchPlan 与版本化多轮 ConstraintPatch；
- 请求级动态 Tool 集、宽搜/标签精搜并行 fan-out、参数修复、无进展检测、候选复用和复杂 Query Eval；
- OpenAI-compatible `LlmClient` Adapter 与版本化 Prompt；默认确定性 Provider 保留为无 Key 回归基线；
- Redis fencing/owner-CAS、失主接管、跨实例取消、优雅停机和旧 owner 提交隔离；
- Agent 终态事务 Outbox、Kafka 幂等审计消费、模型/Tool Bulkhead 与固定故障注入；
- 隔离 Shadow、Redis 跨实例快速开关，以及 Token/成本 Trace 与版本化 Metrics；
- 发现、Search、Feed 与 Agent 候选的真实曝光/主动行为采集，Interaction API 批次幂等、完整归因、事务 Outbox、Kafka 重放与幂等行为事实；
- C 端“发现”已通过同源 Bridge 接通推荐、关键词搜索和任务型多轮 AI 搜索；AI 搜索保存 Session/Goal 版本、支持追问与取消，并复用 Search Tool 的真实候选，B 端“用户画像／内容工作台”保持后端联调；
- Spring MVC 普通返回值、JDBC/HikariCP、同步 Redis/Elasticsearch Adapter，以及推荐局部有界并发。

Agent Phase 3 已经完成，Phase 4 的行为事实前置也已完成。OpenAI-compatible Adapter 现兼容标准 `message.content` 与 LongCat 的 `message.reasoning_content`；本地已用 LongCat-2.0 跑通一次模型 → Agent → Search Tool → Web 的真实功能验收，并观测到 Provider 返回的 Token usage。默认固定评测仍使用可复现的确定性 Provider，这次验收不冒充质量或成本基线。HITL、Handoff、子 Agent、MCP、Checkpoint 精确恢复、写 Tool 副作用账本、上下文压缩、流式 Push 和完整 OpenTelemetry 仍未完成；Ark-Leto 反向核对矩阵见 [ADR-006](../adr/ADR-006-agent-reliability-fencing-outbox-shadow.md)。

运行模型决策见 [ADR-002：命令式应用运行模型与局部有界并发](../adr/ADR-002-imperative-application-runtime.md)。普通 Search/Feed 保持同步 JSON；未来 Agent 的模型调用和 Tool fan-out 只能在 Agent 边界内使用明确、有界、可观测的并发，不把 `Mono`/`Flux` 重新扩散到业务接口。

Agent 内核决策和参考文档映射见 [ADR-004](../adr/ADR-004-ark-leto-inspired-agent-runtime.md) 与 [`docs/agent-runtime.md`](../agent-runtime.md)。项目没有 Ark-Leto 源码或运行时依赖，当前是参考其主链路原理自行实现的内部框架。

## Step 与 Agent Phase 的对应关系

开发 `Step` 是仓库中的交付顺序；Agent `Phase` 是 [`SeekFlux.md`](../../SeekFlux.md) 中的能力成熟度。两者不是同一套编号。

| 开发 Step | 对应 Agent Phase | 交付结果 | 状态 |
| --- | --- | --- | --- |
| Step 0：工程基线 | 前置工程能力 | 构建、模块边界、契约和本地环境 | 已完成 |
| Step 1：内容登记与画像发布 | Agent Phase 0 的数据前置 | 可检索内容画像的可靠生产链路 | 已完成 |
| Step 2：关键词搜索 | Agent Phase 0 的一部分 | 可解释 Direct Search 基线 | 已完成 |
| Step 3：Feed 基线 | Agent Phase 4 的提前历史切片 | 热门、兴趣、相似召回及规则排序 | 已完成 |
| Step 4：Agent-ready Direct Search | 完成 Agent Phase 0 | 可复现、可评测、可追踪、可回退的 Direct Search | 已完成 |
| Step 5：Agent Runtime MVP | Agent Phase 1 | 通用有限步 Runtime、Search Agent、Session 和基础 Eval | 已完成 |
| Step 6：复杂 Search Agent | Agent Phase 2 | Query Mode Router、多轮约束、动态工具、多路 Tool 和确定性回退 | 已完成 |
| Step 7：Agent 可靠性与平台化 | Agent Phase 3 | 多实例执行权、恢复、故障注入、Shadow 和成本治理 | 已完成 |
| Step 8：曝光与行为闭环 | Agent Phase 4 可选深化 | 可归因、幂等、可回放的行为事实 | 已完成 |
| **Step 9：实时特征与短期兴趣** | **Agent Phase 4 可选深化** | **Kafka/Flink 窗口和在线兴趣** | **下一步** |
| Step 10：模型排序与推荐实验 | Agent Phase 4 可选深化 | 训练、模型发布、推荐实验和效果闭环 | 后置可选 |

Step 3 提前实现 Feed 是已经发生的项目事实，不需要删除或伪装成未完成。Agent 主线已经完成到 Step 7，Step 8 已补齐后续深化所需的行为事实；接下来按实时兴趣 → 模型实验的依赖顺序推进，不直接跳到复杂推荐模型。

## 下一阶段：Step 9 实时特征与短期兴趣

Step 8 已经把曝光与主动行为建立为可归因、幂等、可回放的 PostgreSQL/Kafka 事实。Step 9 只消费这些权威输入，建立分钟到小时窗口的短期兴趣和在线可读快照；不能从前端本地队列直接计算，也不能用批处理结果冒充实时链路。

计划范围：

1. 冻结实时特征定义、窗口、权重、衰减、版本与最大新鲜度；
2. 消费版本化 Interaction Topic，处理事件时间、Watermark、允许乱序和迟到补偿；
3. 生成用户短期兴趣与内容热度快照，并通过稳定读取 Port 写入 Online Store；
4. Search/Feed 在明确开关下读取实时特征，过期或不可用时确定性回退到现有画像和规则排序；
5. 用固定事件序列验证重放一致、窗口边界、迟到语义、新鲜度与回退，不以一次页面体验代替评测。

完成门槛：固定事件流能生成版本化短期兴趣快照；重复与可接受乱序不改变最终窗口结果，超出 Watermark 的事件有明确补偿或丢弃事实；在线读取携带 `computedAt` 并执行新鲜度保护；实时链路故障时 Search/Feed 保持现有确定性回退；契约、自动化测试、固定评测和真实联调共同支撑后才标记完成。

## 阅读入口

- [Step 0：工程基线](step-00-engineering-baseline.md)：理解 Maven、模块化单体、六边形边界和环境基线。
- [Step 1：内容登记与画像发布](step-01-content-pipeline.md)：理解状态机、JDBC 事务、Outbox、Kafka 和幂等 Worker。
- [Step 2：已发布画像的关键词检索](step-02-keyword-search.md)：理解事件驱动索引、Direct Search 和 Agent Tool 的确定性底座。
- [Step 3：热门、兴趣与相似内容 Feed](step-03-feed-baseline.md)：理解已完成的推荐历史切片及其与 Agent 主线的边界。
- [Step 4：Agent-ready Direct Search](step-04-agent-ready-direct-search.md)：理解双路检索、RRF、Deadline、Trace、约束和真实评测。
- [Step 5：Agent Runtime MVP](step-05-agent-runtime-mvp.md)：理解参考 Ark-Leto 主链路自研的 Runtime、会话执行权、事件分层、Search Tool 和基础 Eval。
- [Step 6：复杂 Search Agent](step-06-complex-search-agent.md)：理解 Query Mode、多轮目标、动态并行 Tool、候选复用和复杂 Query Eval。
- [Step 7：Agent 可靠性与平台化](step-07-agent-reliability-platform.md)：理解 fencing、强恢复、分布式取消、事务 Outbox、Shadow、故障与 SLO。
- [Step 8：曝光与行为闭环](step-08-interaction-loop.md)：理解批次/事件双层幂等、完整曝光归因、事务 Outbox、Kafka 重放与行为事实。
- [阶段学习文档模板](template.md)：以后每完成一个可运行切片时使用。

## 完成状态规则

一个开发切片只有同时满足以下条件才能标记为“已完成”：

- 功能代码、自动化测试和必要契约已经实现；
- 真实链路或固定评测数据完成验收，结果可以复现；
- 本页更新当前 Step、下一步和对应 Agent Phase；
- 新增或更新阶段文档，明确已实现、未实现、失败语义和完成证据；
- 长期架构决策进入 `docs/adr/`，API/事件变化进入 `contracts/`；
- Agent/检索效果进入 `evals/`，不能只用页面截图或主观体验宣称提升。

状态只使用四种：`已完成`、`下一步`、`未开始`、`后置可选`。规划中的模块不能因为目录已预留、接口已设计或文档已写就标记为完成。

文件按完成顺序新增；未来 Step 的学习文档在开始实现时创建，避免计划被误读成事实：

```text
docs/learning/
├── README.md
├── step-00-engineering-baseline.md
├── step-01-content-pipeline.md
├── step-02-keyword-search.md
├── step-03-feed-baseline.md
├── step-04-agent-ready-direct-search.md
├── step-05-agent-runtime-mvp.md
├── step-06-complex-search-agent.md
├── step-07-agent-reliability-platform.md
├── step-08-interaction-loop.md
└── template.md
```

文档职责保持单一：本页维护当前状态和完整路线；每个 `step-NN-*.md` 只记录对应切片；`template.md` 只作为新 Step 模板。不要再增加架构路线副本、阶段总结副本或同一 Step 的多份说明。
