# ADR-003：Agent-ready Direct Search 采用双路检索与确定性回退

- 状态：Accepted
- 日期：2026-08-08

## 背景

Step 2 的 Search Adapter 直接返回关键词分页结果，Search Context 没有候选融合、通道隔离、Deadline、结构化 Trace 或固定评测。未来 Agent 如果直接调用这条链路，无法量化 Agent 相对 Direct Search 的增益，也无法在检索通道失败时提供稳定退路。

## 决策

1. Search Context 负责 Direct Search 编排；Elasticsearch Adapter 只实现 `KEYWORD` 与 `SEMANTIC` 两个可替换检索通道。
2. `KEYWORD` 使用字段加权 BM25/补召回，`SEMANTIC` 首期使用 64 维 Hashing n-gram Encoder 与 Elasticsearch kNN。该 Encoder 只是确定性 ANN 基线，不声称具有预训练模型的语义效果，后续可经 `SemanticVectorEncoder` 替换。
3. 两路候选在应用层使用带通道权重的 RRF 融合，不直接相加 Elasticsearch 原始分数。
4. 两路检索运行在 Search 专用有界线程池，共享请求 Deadline；超时任务会取消。单路失败返回另一通道并标记降级，双路失败返回 HTTP 503 `SEARCH_UNAVAILABLE`。
5. 每次成功请求返回 Search Trace，包含执行模式、索引/策略/Retriever 版本、通道状态、耗时、候选数和降级来源。
6. Query 统一进行 NFKC 规范化、长度/分页窗口约束，并支持最多 10 个 `requiredTags` 精确条件；返回前继续执行阻断标签过滤。
7. 固定数据、分级相关性标签、真实 API Runner 和结果 Artifact 进入 `evals/`，版本结果必须能够关联数据集、索引、策略和 Retriever。

## 后果

- Direct Search 在没有 Agent Server、模型服务或会话模块时仍能独立运行，并可作为未来 Search Agent 的 Tool 和确定性回退。
- Hashing 向量能验证索引、ANN、融合与降级架构，但效果上限有限；引入预训练 Embedding 时必须保留当前数据集和结果作为对照。
- 候选集首期限制为 200，页码窗口也限制在 200 内；规模化深分页仍需 Retriever 原生 Cursor。
- `seekflux-content-search-index-v2` 消费组会回放发布事件，为已有文档补写向量；upsert 和撤回处理仍保持幂等。
