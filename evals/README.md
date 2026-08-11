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

该 Runner 使用数据集里的 `requiredTags` 隔离共享本地索引，分别调用 8080 Direct Search 与 8083 Agent Search。除相关性指标外，还校验 Agent 稳定终态、冻结的 Agent/Tool 版本、有限步 Tool 主链，以及最终所选 Tool 的 Agent Trace 到 Search Trace 关联。Phase 1 历史结果保留在 `evals/results/agent-search-v1-baseline.json`；当前 Runtime 回归写入 `evals/results/agent-search-v2-regression.json`，避免覆盖旧版本事实。

复杂 Search Agent 使用对抗关键词陷阱、简单路由和多轮补丁数据集：

```bash
python3 evals/run_complex_agent_eval.py \
  --min-mrr-gain 0.5 \
  --min-tool-selection-accuracy 1.0
```

Runner 通过真实 Content → Worker → Elasticsearch 链路创建 12 条临时内容，并校验 6 条复杂 Query 的 Direct/Agent Top-1 指标、SearchPlan 标签、并行 Tool Trace、候选复用、简单 Query 直达和 `ConstraintPatch` 版本冲突。默认结果写入 `evals/results/complex-search-v1-baseline.json`。该结果使用确定性 Provider，衡量编排和结构化检索增量，不代表真实模型成本或泛化效果。

Agent 可靠性基线复用同一版本化内容集，并增加 PostgreSQL/Outbox/审计断言：

```bash
python3 evals/run_agent_reliability_eval.py
```

Runner 会自行发布样本、等待 Kafka/Worker/Elasticsearch 链路完成并在退出时清理；它校验单会话并发写、fencing token 单调、重复请求无额外 Run/Tool 事件、事务 Outbox、幂等审计消费、Shadow 主结果隔离/快速关闭，以及可用性、P95 和 Fallback。默认结果写入 `evals/results/agent-reliability-v1-baseline.json`。确定性 Provider 不报告 usage 时，报告必须保留 `providerUsageMeasured=false`，不能把 0 Token/成本解释成真实模型免费。

运行前需要 PostgreSQL、Redis、Kafka、Elasticsearch、Content Server、Worker Runner、Online Server 均健康；Agent 对照评测还需要 Agent Server。若只想保留临时内容用于排查，可增加 `--keep-fixtures`。

Step 8 行为闭环使用固定内容和真实 Interaction API → Outbox → Kafka → Worker → PostgreSQL 事实链路：

```bash
python3 evals/run_interaction_loop_eval.py
```

Runner 校验新批次接入、同键同体重放、同键异体冲突、事件级去重、曝光到主动行为的完整归因、非法归因与乱序拒绝、撤回内容拒绝，以及 Kafka 重放不增加最终事实。默认结果写入 `evals/results/interaction-loop-v1-baseline.json`，临时内容与行为记录会在结束时清理。

Step 9 实时特征使用固定内容与固定事件时间序列，贯穿 Interaction API、Outbox、Kafka、窗口投影、Redis、Search 和 Feed：

```bash
python3 evals/run_realtime_feature_eval.py
```

Runner 在调用排序前等待内容发布、Elasticsearch 可见和在线热度快照三道屏障，避免把异步索引延迟误判为排序失败。它校验版本化新鲜快照、短期兴趣和内容热度、Search/Feed 实际消费、Kafka 重放幂等、允许乱序、超界迟到、过期快照与 Redis 损坏时的确定性回退。默认结果写入 `evals/results/realtime-features-v1-baseline.json`，临时内容、行为、快照和 Redis Key 会在结束时清理。
