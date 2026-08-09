# SeekFlux 学习路线与实现日志

这组文档是项目唯一的进度入口和按序实现日志。目标架构以 [`SeekFlux.md`](../../SeekFlux.md) 为准，依赖规则以[模块边界](../module-boundaries.md)为准；这里不再复制架构总览，只负责回答四个执行问题：

1. 当前已经由代码和测试证明了什么；
2. 现在处于哪个开发 Step，对应 Agent 路线的哪个 Phase；
3. 下一步只做什么，完成门槛是什么；
4. 完成后怎样通过代码、测试、评测和练习真正掌握它。

## 当前进度

> **当前处于：Step 6 已完成，下一步进入 Step 7「Agent 可靠性与平台化」。**

截至 2026-08-08，已经跑通以下真实链路：

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
- C 端“发现”和 B 端“用户画像／内容工作台”通过同源 Bridge 与后端联调；
- Spring MVC 普通返回值、JDBC/HikariCP、同步 Redis/Elasticsearch Adapter，以及推荐局部有界并发。

Agent Phase 2 已经完成。真实 Provider 协议 Adapter 已接入并通过本地协议测试，但默认评测仍使用可复现的确定性 Provider；尚未形成真实模型 Token、成本与质量基线。多实例失主接管、fencing、跨实例取消、事务 Outbox、Shadow、HITL、子 Agent、MCP、流式 Push 和完整 OpenTelemetry 仍未完成，不能因已有租约或 SPI 就提前宣称具备这些能力。

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
| **Step 7：Agent 可靠性与平台化** | **Agent Phase 3** | **多实例执行权、恢复、故障注入、Shadow 和成本治理** | **下一步** |
| Step 8：曝光与行为闭环 | Agent Phase 4 可选深化 | 可归因、幂等、可回放的行为事实 | 后置可选 |
| Step 9：实时特征与短期兴趣 | Agent Phase 4 可选深化 | Kafka/Flink 窗口和在线兴趣 | 后置可选 |
| Step 10：模型排序与推荐实验 | Agent Phase 4 可选深化 | 训练、模型发布、推荐实验和效果闭环 | 后置可选 |

Step 3 提前实现 Feed 是已经发生的项目事实，不需要删除或伪装成未完成；但它不再决定下一阶段。Agent 主线完成到 Step 7 之前，不因追求推荐技术栈完整而优先建设 Flink、行为模型或复杂推荐实验。

## 下一阶段：Step 7 Agent 可靠性与平台化

Step 6 已经证明简单 Query 可以绕过 Agent，复杂 Query 可以通过结构化约束和并行 Search Tool 获得可复现增量，多轮目标也具备原子版本校验。Step 7 开始验证多副本和故障条件下的一致性、恢复、治理与 SLO。

计划范围：

1. 为执行权增加 fencing token，并验证失主实例不能在租约失效后继续提交结果；
2. 实现实例接管时的强一致恢复、跨实例取消和优雅停机；
3. 用事务 Outbox 发布 Agent 完成/回退事实，增加幂等评测与审计消费者；
4. 增加模型/Tool Bulkhead、超时与故障注入，验证部分成功和 Direct Fallback；
5. 建立 Agent/Prompt/Tool 策略 Shadow、小流量切换与快速回滚；
6. 记录真实 Provider 的 Token、成本、P95、Fallback 和版本关联，形成容量与 SLO 基线。

完成门槛：至少双实例下同一 Session 不双写，失主可接管且旧 owner 不能提交；重复请求与重放不产生重复 Tool 副作用；跨实例取消、优雅停机和模型/Tool/Redis 故障注入有自动化证据；Shadow 不影响主结果并可快速关闭；Agent/Direct 的可用性、P95、Fallback、Token 与成本形成版本化报告。

## 阅读入口

- [Step 0：工程基线](step-00-engineering-baseline.md)：理解 Maven、模块化单体、六边形边界和环境基线。
- [Step 1：内容登记与画像发布](step-01-content-pipeline.md)：理解状态机、JDBC 事务、Outbox、Kafka 和幂等 Worker。
- [Step 2：已发布画像的关键词检索](step-02-keyword-search.md)：理解事件驱动索引、Direct Search 和 Agent Tool 的确定性底座。
- [Step 3：热门、兴趣与相似内容 Feed](step-03-feed-baseline.md)：理解已完成的推荐历史切片及其与 Agent 主线的边界。
- [Step 4：Agent-ready Direct Search](step-04-agent-ready-direct-search.md)：理解双路检索、RRF、Deadline、Trace、约束和真实评测。
- [Step 5：Agent Runtime MVP](step-05-agent-runtime-mvp.md)：理解参考 Ark-Leto 主链路自研的 Runtime、会话执行权、事件分层、Search Tool 和基础 Eval。
- [Step 6：复杂 Search Agent](step-06-complex-search-agent.md)：理解 Query Mode、多轮目标、动态并行 Tool、候选复用和复杂 Query Eval。
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
└── template.md
```

文档职责保持单一：本页维护当前状态和完整路线；每个 `step-NN-*.md` 只记录对应切片；`template.md` 只作为新 Step 模板。不要再增加架构路线副本、阶段总结副本或同一 Step 的多份说明。
