# ADR-001：首期采用模块化单体与独立 Worker

- 状态：Accepted
- 日期：2026-08-02

## 决策

普通在线 Search/Feed 采用一个可多副本部署的 Spring Boot 进程，内容控制面采用独立进程，资源密集与异步任务由 Worker、可选 Flink 和训练 Runner 承担。当前业务边界由十个 Context 模块和六边形依赖规则保证。

Step 5 已新增独立 `agent-server` 进程，装配第十个 `AgentOrchestration` Context 与 `platform/agent-runtime`。单独部署的原因是 LLM 长延迟、成本、取消、限流和故障隔离特征不同；这不意味着每个 Tool、Retriever 或 Context 都要拆成微服务。Direct Search 继续由 `online-server` 独立提供，不依赖 Agent。

## 原因

早期规模不足以抵消全面微服务带来的部署、契约、观测和一致性成本，但内容处理、实时计算、确定性在线请求与 Agent 长任务有不同的资源和故障特征，因此按运行形态拆少量进程，不拆所有业务 Context。

## 后果

应用装配层会依赖多个 Context；Context 不共享可变实体；基础设施通过输出 Port 接入。Agent Tool 只能调用业务输入 Port，不能越过 Context 直接访问存储。未来满足独立扩缩容、发布或故障隔离条件时，可沿稳定 Context 边界拆分。
