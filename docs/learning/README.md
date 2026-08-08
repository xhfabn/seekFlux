# SeekFlux 学习路线与实现日志

这组文档是项目唯一的进度入口和按序实现日志。目标架构以 [`SeekFlux.md`](../../SeekFlux.md) 为准，依赖规则以[模块边界](../module-boundaries.md)为准；这里不再复制架构总览，只负责回答四个执行问题：

1. 当前已经由代码和测试证明了什么；
2. 现在处于哪个开发 Step，对应 Agent 路线的哪个 Phase；
3. 下一步只做什么，完成门槛是什么；
4. 完成后怎样通过代码、测试、评测和练习真正掌握它。

## 当前进度

> **当前处于：Step 5 已完成，下一步进入 Step 6「复杂 Search Agent」。**

截至 2026-08-08，已经跑通以下真实链路：

- 内容登记 → PostgreSQL/Outbox → Kafka → Worker 生成画像并发布 → Elasticsearch 建索引；
- 用户画像保存到 Redis → Search/Feed 使用真实后端数据；
- 关键词搜索，以及热门、兴趣、相似内容召回、画像标签强匹配、规则排序、Cursor 和单路降级；
- Direct Search 的 BM25/kNN 双路召回、RRF 融合、结构化约束、共同 Deadline、单路降级和版本化 Search Trace；
- 自研 Agent Runtime 主链路 `Router → FeaturePipeline → SessionExecutor → AgentLoop`，以及有限步、共同 Deadline、Tool Schema、稳定终态和版本冻结；
- PostgreSQL Session 追加事件与独立运行事件、Redis 执行权/热投影、重复请求保护和同步取消入口；
- 两个配置化 AgentDef、Search Tool Adapter、追问、Agent → Direct Fallback、Agent/Search 双 Trace 和 Direct/Agent 对照 Eval；
- C 端“发现”和 B 端“用户画像／内容工作台”通过同源 Bridge 与后端联调；
- Spring MVC 普通返回值、JDBC/HikariCP、同步 Redis/Elasticsearch Adapter，以及推荐局部有界并发。

Agent Phase 1 已经完成，但当前决策 Provider 是用于可复现验收的本地确定性实现，不是真实大模型。复杂 Query 路由、多轮约束补丁、动态/并行 Tool、多实例恢复、跨实例取消、HITL、子 Agent、MCP、流式 Push 和完整 OpenTelemetry 仍未完成，不能因已有扩展接口就提前宣称具备这些能力。

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
| **Step 6：复杂 Search Agent** | **Agent Phase 2** | **Query Mode Router、多轮约束、动态工具、多路 Tool 和确定性回退** | **下一步** |
| Step 7：Agent 可靠性与平台化 | Agent Phase 3 | 多实例执行权、恢复、故障注入、Shadow 和成本治理 | 未开始 |
| Step 8：曝光与行为闭环 | Agent Phase 4 可选深化 | 可归因、幂等、可回放的行为事实 | 后置可选 |
| Step 9：实时特征与短期兴趣 | Agent Phase 4 可选深化 | Kafka/Flink 窗口和在线兴趣 | 后置可选 |
| Step 10：模型排序与推荐实验 | Agent Phase 4 可选深化 | 训练、模型发布、推荐实验和效果闭环 | 后置可选 |

Step 3 提前实现 Feed 是已经发生的项目事实，不需要删除或伪装成未完成；但它不再决定下一阶段。Agent 主线完成到 Step 7 之前，不因追求推荐技术栈完整而优先建设 Flink、行为模型或复杂推荐实验。

## 下一阶段：Step 6 复杂 Search Agent

Step 5 已经证明两个 AgentDef 能复用同一 Runtime，并通过标准 Tool 调用 Direct Search。Step 6 开始验证 Agent 对复杂 Query 的业务增量，而不是继续堆叠 Runtime 骨架。

计划范围：

1. 接入至少一个真实 `LlmClient` Provider Adapter 和版本化 Prompt，同时保留确定性 Provider 作为回归基线；
2. 增加 Query Mode Router，让简单 Query 保持 Direct，复杂 Query 才进入 Agent；
3. 把 SearchGoal/QueryConstraintSet 扩展为可版本校验的多轮 `ConstraintPatch`，支持缺参追问与“放宽到五分钟”等修正；
4. 根据意图动态暴露标准 Tool，并增加受共同 Deadline、次数和并发上限约束的 Tool fan-out；
5. 增加参数修复、无进展检测、候选复用和可解释 Agent → Direct Fallback；
6. 建立复杂 Query/多轮数据集，比较 Direct 与 Agent 的 Tool 正确率、任务完成率、Recall、零结果率、延迟和成本。

完成门槛：复杂 Query 相对 Direct 基线有可复现增益；简单 Query 不承担不可接受的 Agent 延迟；多轮 ConstraintPatch 有版本冲突测试；动态 Tool/并行调用仍受共同 Deadline；模型或全部 Tool 故障时返回带原因的 Direct Fallback；评测结果关联 Agent/Prompt/模型/Tool/Search 版本。

## 阅读入口

- [Step 0：工程基线](step-00-engineering-baseline.md)：理解 Maven、模块化单体、六边形边界和环境基线。
- [Step 1：内容登记与画像发布](step-01-content-pipeline.md)：理解状态机、JDBC 事务、Outbox、Kafka 和幂等 Worker。
- [Step 2：已发布画像的关键词检索](step-02-keyword-search.md)：理解事件驱动索引、Direct Search 和 Agent Tool 的确定性底座。
- [Step 3：热门、兴趣与相似内容 Feed](step-03-feed-baseline.md)：理解已完成的推荐历史切片及其与 Agent 主线的边界。
- [Step 4：Agent-ready Direct Search](step-04-agent-ready-direct-search.md)：理解双路检索、RRF、Deadline、Trace、约束和真实评测。
- [Step 5：Agent Runtime MVP](step-05-agent-runtime-mvp.md)：理解参考 Ark-Leto 主链路自研的 Runtime、会话执行权、事件分层、Search Tool 和基础 Eval。
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
└── template.md
```

文档职责保持单一：本页维护当前状态和完整路线；每个 `step-NN-*.md` 只记录对应切片；`template.md` 只作为新 Step 模板。不要再增加架构路线副本、阶段总结副本或同一 Step 的多份说明。
