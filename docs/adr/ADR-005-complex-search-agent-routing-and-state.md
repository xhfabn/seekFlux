# ADR-005：复杂 Search Agent 的路由、状态与候选复用

- 状态：Accepted
- 日期：2026-08-08

## 背景

Phase 1 已证明 Runtime 能有限步调用 Search Tool，但所有查询都进入 Agent，SearchGoal 也没有可校验的多轮状态。Phase 2 需要让 Agent 只承担复杂查询，并证明它相对 Direct Search 的业务增量，同时不能让模型在 Search Context 之外自行过滤或重排候选。

## 决策

1. `/v1/agent/search` 接受 `AUTO | DIRECT | AGENT`。`AUTO` 中，简单查询直接调用 `SearchUseCase`；复杂自然语言或 `ConstraintPatch` 才进入 Agent。显式 `DIRECT` 不允许携带多轮补丁。
2. `SearchIntentAnalyzer@search-intent-rules-v1` 生成结构化 `SearchPlan`，包含改写 Query、派生标签、复杂度和原因。它是可替换的确定性基线，不冒充通用语言理解模型。
3. SearchGoal 使用单调递增版本。Agent 路径把 `STATE_PATCHED` 与 `USER_MESSAGE` 在同一 PostgreSQL 事务中追加；`ConstraintPatch.baseVersion` 不匹配时返回 `409 AGENT_CONSTRAINT_VERSION_CONFLICT`。
4. Tool 集按本次 `SearchPlan` 动态取 AgentDef 允许集的子集，并在运行开始时冻结版本。简单 Agent 请求只暴露 `search_direct`；复杂请求暴露 `search_direct + search_filtered`。
5. 复杂请求并行执行宽搜与标签精搜，二者都只调用同一个 `SearchUseCase`。Agent 最终选择并原样复用一个 Tool 的候选集，返回 `selectedTool`、`successfulToolCount` 和 `candidateSetReused`，不在 Agent 层重排 Search 结果。
6. Tool 参数只允许一次确定性修复；重复的规范化 Tool 调用指纹触发 `NO_PROGRESS_DETECTED`。所有分支共同受 Deadline、最大步骤、最大 Tool 次数和有界执行器约束；部分 Tool 成功可继续，全部失败走可解释 Direct Fallback。
7. 增加 OpenAI-compatible Chat Completions `LlmClient` Adapter，配置项选择 Provider。默认继续使用确定性 Provider 完成无 Key 回归与效果 Eval；真实端点的网络、凭据和模型输出不成为本地测试前提。

## 后果

- 简单查询没有 Agent Loop 与 LLM 延迟，复杂查询才消耗 Agent 预算。
- Workspace 状态具备原子提交和乐观版本冲突语义；当时后置的跨实例失主接管、fencing 与恢复已在 [ADR-006](ADR-006-agent-reliability-fencing-outbox-shadow.md) 完成。
- Agent 的增益可归因于结构化意图与标准 Search Tool，而不是无法解释的 Agent 侧重排。
- 确定性规则的标签词表仍有限；真实 Provider 已有 Adapter，但尚未形成真实模型的在线质量、Token 和成本基线。
