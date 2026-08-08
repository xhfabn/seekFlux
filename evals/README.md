# SeekFlux 评测资产

`evals/` 保存可版本化、可复现的效果证据，不保存只依赖人工观感的截图。

Direct Search 基线使用 [`datasets/direct-search-v1.json`](datasets/direct-search-v1.json) 中的固定内容、Query 和分级相关性标注。Runner 会通过真实 Content API 创建临时内容，等待 Worker 发布和索引，再调用真实 Search API 计算 `Precision@K`、`Recall@K`、`MRR@K`、`nDCG@K` 和零结果率；默认在结束时撤回临时内容。

```bash
python3 evals/run_direct_search_eval.py \
  --require-hybrid \
  --min-recall 1.0
```

默认结果写入 `evals/results/direct-search-v1-baseline.json`。报告保留数据集、索引、策略、Retriever 版本和每个通道状态；比较两个版本时必须固定数据集与 `K`，不能只比较聚合数字。

Agent Runtime 使用同一数据集做 Direct/Agent 对照：

```bash
python3 evals/run_agent_search_eval.py \
  --min-agent-recall 1.0 \
  --min-top1-agreement 1.0
```

该 Runner 使用数据集里的 `requiredTags` 隔离共享本地索引，分别调用 8080 Direct Search 与 8083 Agent Search。除相关性指标外，还校验 Agent 稳定终态、冻结的 Agent/Tool 版本、`CALL_TOOL → COMPLETE` 主链和 Agent Trace 到 Search Trace 的关联。结果写入 `evals/results/agent-search-v1-baseline.json`。

运行前需要 PostgreSQL、Redis、Kafka、Elasticsearch、Content Server、Worker Runner、Online Server 均健康；Agent 对照评测还需要 Agent Server。若只想保留临时内容用于排查，可增加 `--keep-fixtures`。
