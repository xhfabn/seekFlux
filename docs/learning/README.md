# SeekFlux 学习路线与实现日志

这组文档是项目唯一的进度入口和按序实现日志。目标架构以 [`SeekFlux.md`](../../SeekFlux.md) 为准，依赖规则以[模块边界](../module-boundaries.md)为准；这里不再复制架构总览，只负责回答四个执行问题：

1. 当前已经由代码和测试证明了什么；
2. 现在处于哪个开发 Step，对应 Agent 路线的哪个 Phase；
3. 下一步只做什么，完成门槛是什么；
4. 完成后怎样通过代码、测试、评测和练习真正掌握它。

## 当前进度

> **当前处于：Step 4 已完成，下一步进入 Step 5「Agent Runtime MVP」。**

截至 2026-08-08，已经跑通以下真实链路：

- 内容登记 → PostgreSQL/Outbox → Kafka → Worker 生成画像并发布 → Elasticsearch 建索引；
- 用户画像保存到 Redis → Search/Feed 使用真实后端数据；
- 关键词搜索，以及热门、兴趣、相似内容召回、画像标签强匹配、规则排序、Cursor 和单路降级；
- Direct Search 的 BM25/kNN 双路召回、RRF 融合、结构化约束、共同 Deadline、单路降级和版本化 Search Trace；
- C 端“发现”和 B 端“用户画像／内容工作台”通过同源 Bridge 与后端联调；
- Spring MVC 普通返回值、JDBC/HikariCP、同步 Redis/Elasticsearch Adapter，以及推荐局部有界并发。

当前还没有 Agent 代码：`apps/agent-server`、`contexts/agent-orchestration-context`、`platform/agent-runtime`、Search Tool Adapter 和 Agent Eval 均未创建。现在只能说 Agent 已完成架构设计，不能说 Agent MVP 已实现。

运行模型决策见 [ADR-002：命令式应用运行模型与局部有界并发](../adr/ADR-002-imperative-application-runtime.md)。普通 Search/Feed 保持同步 JSON；未来 Agent 的模型调用和 Tool fan-out 只能在 Agent 边界内使用明确、有界、可观测的并发，不把 `Mono`/`Flux` 重新扩散到业务接口。

## Step 与 Agent Phase 的对应关系

开发 `Step` 是仓库中的交付顺序；Agent `Phase` 是 [`SeekFlux.md`](../../SeekFlux.md) 中的能力成熟度。两者不是同一套编号。

| 开发 Step | 对应 Agent Phase | 交付结果 | 状态 |
| --- | --- | --- | --- |
| Step 0：工程基线 | 前置工程能力 | 构建、模块边界、契约和本地环境 | 已完成 |
| Step 1：内容登记与画像发布 | Agent Phase 0 的数据前置 | 可检索内容画像的可靠生产链路 | 已完成 |
| Step 2：关键词搜索 | Agent Phase 0 的一部分 | 可解释 Direct Search 基线 | 已完成 |
| Step 3：Feed 基线 | Agent Phase 4 的提前历史切片 | 热门、兴趣、相似召回及规则排序 | 已完成，保留但不阻塞 Agent |
| Step 4：Agent-ready Direct Search | 完成 Agent Phase 0 | 可复现、可评测、可追踪、可回退的 Direct Search | 已完成 |
| **Step 5：Agent Runtime MVP** | **Agent Phase 1** | **通用有限步 Runtime、Search Agent、Session 和基础 Eval** | **下一步** |
| Step 6：复杂 Search Agent | Agent Phase 2 | Router、多轮约束、动态工具、多路 Tool 和确定性回退 | 未开始 |
| Step 7：Agent 可靠性与平台化 | Agent Phase 3 | 多实例执行权、恢复、故障注入、Shadow 和成本治理 | 未开始 |
| Step 8：曝光与行为闭环 | Agent Phase 4 可选深化 | 可归因、幂等、可回放的行为事实 | 后置可选 |
| Step 9：实时特征与短期兴趣 | Agent Phase 4 可选深化 | Kafka/Flink 窗口和在线兴趣 | 后置可选 |
| Step 10：模型排序与推荐实验 | Agent Phase 4 可选深化 | 训练、模型发布、推荐实验和效果闭环 | 后置可选 |

Step 3 提前实现 Feed 是已经发生的项目事实，不需要删除或伪装成未完成；但它不再决定下一阶段。Agent 主线完成到 Step 7 之前，不因追求推荐技术栈完整而优先建设 Flink、行为模型或复杂推荐实验。

## 下一阶段：Step 5 Agent Runtime MVP

Step 4 已经保证 Direct Search 能独立评测和回退。Step 5 才正式创建 Agent 代码，并保持 Runtime 与 Search 业务语义分离。

计划范围：

1. 创建 `apps/agent-server`、`contexts/agent-orchestration-context` 和 `platform/agent-runtime`；
2. 实现与 Search 无关的有限步 Runtime Core、AgentDef、Tool Registry、Deadline 和稳定终态；
3. 让 Search Agent 只通过 Search Tool Adapter 调用现有 Search Use Case；
4. 建立最小 Session 状态、运行事件和结构化 Agent Trace；
5. 至少两个配置化 AgentDef 复用同一 Runtime，其中 Search Agent 跑通完整业务；
6. 建立 Direct/Agent 对照数据和基础 Agent Eval，不用对话页面代替评测。

完成门槛：Runtime Core 不依赖 Search 或具体模型 SDK；有限步、Tool 参数校验、超时和稳定终态有自动化测试；Search Agent 完整复用 Direct Search Trace；Agent 故障仍能回退到 Direct Search；Session 和运行事件可追踪；Direct/Agent 对照结果可复现。

## 阅读入口

- [Step 0：工程基线](step-00-engineering-baseline.md)：理解 Maven、模块化单体、六边形边界和环境基线。
- [Step 1：内容登记与画像发布](step-01-content-pipeline.md)：理解状态机、JDBC 事务、Outbox、Kafka 和幂等 Worker。
- [Step 2：已发布画像的关键词检索](step-02-keyword-search.md)：理解事件驱动索引、Direct Search 和 Agent Tool 的确定性底座。
- [Step 3：热门、兴趣与相似内容 Feed](step-03-feed-baseline.md)：理解已完成的推荐历史切片及其与 Agent 主线的边界。
- [Step 4：Agent-ready Direct Search](step-04-agent-ready-direct-search.md)：理解双路检索、RRF、Deadline、Trace、约束和真实评测。
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
└── template.md
```

文档职责保持单一：本页维护当前状态和完整路线；每个 `step-NN-*.md` 只记录对应切片；`template.md` 只作为新 Step 模板。不要再增加架构路线副本、阶段总结副本或同一 Step 的多份说明。
