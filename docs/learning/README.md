# SeekFlux 学习路线与实现日志

这组文档是项目唯一的进度入口和按序实现日志。目标架构以 [`SeekFlux.md`](../../SeekFlux.md) 为准，依赖规则以[模块边界](../module-boundaries.md)为准；这里不再复制架构总览，只负责回答四个执行问题：

1. 当前已经由代码和测试证明了什么；
2. 现在处于哪个开发 Step，对应 Agent 路线的哪个 Phase；
3. 下一步只做什么，完成门槛是什么；
4. 完成后怎样通过代码、测试、评测和练习真正掌握它。

## 当前进度

> **当前处于：Step 10 已完成，Step 11「多模态媒体理解与跨模态检索」已开始、仍为下一步。**

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
- 版本化 `realtime-window-v1`、Flink 事件时间/Watermark/迟到 Side Output、本地 JDBC 参考投影、Redis 在线快照，以及 Search/Feed 的短期兴趣与内容热度消费、过期/故障回退；
- C 端“发现”已通过同源 Bridge 接通推荐、关键词搜索和任务型多轮 AI 搜索；AI 搜索保存 Session/Goal 版本、支持追问与取消，并复用 Search Tool 的真实候选，B 端“用户画像／内容工作台”保持后端联调；
- 视频/图文内容类型、多媒体资源、正文和来源许可字段已经贯穿 Content v2 事件、Elasticsearch、Search/Feed 与 Web；外部媒体可由 Pixabay、Qilin 本地数据或规范化 Manifest 下载到 MinIO 后幂等登记，无标签内容不会自动进入分发；
- Spring MVC 普通返回值、JDBC/HikariCP、同步 Redis/Elasticsearch Adapter，以及推荐局部有界并发。

Agent Phase 3 已经完成，Phase 4 的行为事实与实时特征两个深化切片也已完成。OpenAI-compatible Adapter 现兼容标准 `message.content` 与 LongCat 的 `message.reasoning_content`；本地已用 LongCat-2.0 跑通一次模型 → Agent → Search Tool → Web 的真实功能验收，并观测到 Provider 返回的 Token usage。默认固定评测仍使用可复现的确定性 Provider，这次验收不冒充质量或成本基线。HITL、Handoff、子 Agent、MCP、Checkpoint 精确恢复、写 Tool 副作用账本、上下文压缩、流式 Push 和完整 OpenTelemetry 仍未完成；Ark-Leto 反向核对矩阵见 [ADR-006](../adr/ADR-006-agent-reliability-fencing-outbox-shadow.md)。

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
| Step 9：实时特征与短期兴趣 | Agent Phase 4 可选深化 | Kafka/Flink 窗口、在线兴趣/热度和确定性回退 | 已完成 |
| Step 10：真实媒体入库与可消费内容 | Agent Phase 4 可选深化 | 视频/图文、MinIO、来源审计、幂等导入和消费详情 | 已完成 |
| **Step 11：多模态媒体理解与跨模态检索** | **Agent Phase 4 可选深化** | **共享视觉向量、媒体分段索引和跨模态查询** | **下一步** |
| Step 12：模型排序与推荐实验 | Agent Phase 4 可选深化 | 训练、模型发布、推荐实验和效果闭环 | 未开始 |

Step 3 提前实现 Feed 是已经发生的项目事实，不需要删除或伪装成未完成。Agent 主线已经完成到 Step 7，Step 8～10 已补齐行为事实、在线实时特征和真实可消费媒体。Step 11 先补齐真正读取图片像素和视频画面的跨模态检索，再进入 Step 12 排序实验；不能把媒体结果卡片或文本标签检索冒充多模态能力。

## 下一阶段：Step 11 多模态媒体理解与跨模态检索

Step 10 已补齐可播放视频、可阅读图文、来源审计和真实导入入口，但已有索引仍主要依赖文本。Step 11 已开始加入 SigLIP 共享向量、视频关键帧和媒体分段索引；目前只有代码/单测基线，尚未完成真实模型端到端验收和前端文件查询，所以状态仍是“下一步”。

计划范围：

1. 完成内容/查询文件直传和 C 端图片、视频查询交互；
2. 用固定 SigLIP 2 版本对真实图片与视频关键帧编码，并保存模型、抽帧策略与索引版本；
3. 融合 OCR、ASR、视觉描述、标签和原始视觉相似度，保持各路失败可观察；
4. 建立文字搜图/视频、以图搜图/视频、视频搜视频的固定查询集；
5. 验收索引重建、模型版本迁移、出站安全、资源限流和确定性关闭/故障语义。

完成门槛：真实上传内容能够产生版本化图片/视频分段向量；五种跨模态查询方向均通过固定数据集 Recall@K 门槛；前端能上传查询媒体并跳转命中视频时间点；模型、索引或单路理解能力故障有明确语义；自动化测试、固定评测和真实联调共同支撑后才标记完成。

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
- [Step 9：实时特征与短期兴趣](step-09-realtime-features.md)：理解事件时间、Watermark、窗口、在线快照、新鲜度与确定性回退。
- [Step 10：真实媒体入库与可消费内容](step-10-real-media-ingestion.md)：理解视频/图文契约、外部身份幂等、MinIO、来源审计、标签门槛和消费详情。
- [Step 11：多模态媒体理解与跨模态检索](step-11-multimodal-retrieval.md)：理解共享视觉向量、视频分段索引和跨模态查询。
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
├── step-09-realtime-features.md
├── step-10-real-media-ingestion.md
├── step-11-multimodal-retrieval.md
└── template.md
```

文档职责保持单一：本页维护当前状态和完整路线；每个 `step-NN-*.md` 只记录对应切片；`template.md` 只作为新 Step 模板。不要再增加架构路线副本、阶段总结副本或同一 Step 的多份说明。
