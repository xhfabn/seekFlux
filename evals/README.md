# SeekFlux 评测资产

`evals/` 保存可版本化、可复现的效果证据，不保存只依赖人工观感的截图。

Direct Search 基线使用 [`datasets/direct-search-v1.json`](datasets/direct-search-v1.json) 中的固定内容、Query 和分级相关性标注。Runner 会通过真实 Content API 创建临时内容，等待 Worker 发布和索引，再调用真实 Search API 计算 `Precision@K`、`Recall@K`、`MRR@K`、`nDCG@K` 和零结果率；默认在结束时撤回临时内容。

```bash
python3 evals/run_direct_search_eval.py \
  --require-hybrid \
  --min-recall 1.0
```

默认结果写入 `evals/results/direct-search-v1-baseline.json`。报告保留数据集、索引、策略、Retriever 版本和每个通道状态；比较两个版本时必须固定数据集与 `K`，不能只比较聚合数字。

运行前需要 PostgreSQL、Kafka、Elasticsearch、Content Server、Worker Runner 和 Online Server 均健康。若只想保留临时内容用于排查，可增加 `--keep-fixtures`。
