# ADR-004：参考 Ark-Leto 主链路自研 Agent Runtime

- 状态：Accepted
- 日期：2026-08-08

## 背景

项目需要在 Direct Search 之上建立可复用 Agent Runtime。参考文档《Ark-Leto 框架内核 与 Agentspark 主链路 原理详解》给出了 Router、FeaturePipeline、SessionExecutor、AgentLoop、WorkspaceEvent、执行权和 Tool 调度之间的关键关系，但项目没有 Ark-Leto 源码，也没有把它作为可验证的运行时依赖。

如果直接把文档中的类名复制成 Search 专用流程，Runtime 会与业务语义耦合；如果只实现一个 Controller 内循环，又会丢失会话一致性、幂等、恢复和可观测边界。因此需要吸收其主链路与不变量，同时按 SeekFlux 的六边形模块边界自行实现。

## 决策

1. `platform/agent-runtime` 是自研、业务无关的 Java Runtime Core，不依赖 Search Context、具体模型 SDK、Spring、Redis 或 Elasticsearch。
2. 主链路固定为 `Router → FeaturePipeline → SessionExecutor → AgentLoop`。FeatureNode 使用显式列表和稳定顺序，当前内置节点依次为 SessionLoad、AgentResolve、ParamInit、ResumeEval。
3. 同一 Session 必须先获得执行权，再提交本轮 UserMessage。执行权由 Port 抽象，Redis Adapter 使用带 owner 比较的获取、续租和释放；重复 `requestId` 不再次进入 Loop。
4. PostgreSQL 中的 `WorkspaceEvent` 是 Session 的追加式事实源；AgentRun/RunEvent 是独立的执行轨迹。前端过程 `PushEvent` 不参与 Session 投影，三类事件不能互相替代。
5. `AgentLoop` 每轮都通过 `ContextEngine` 组装上下文，通过厂商无关 `LlmClient` 获取结构化 Decision，通过 Tool Registry/Executor 执行受 Schema、次数和共同 Deadline 限制的工具调用。
6. AgentDef、Prompt、决策提供方和 Tool Schema 版本在运行开始时冻结并进入 Trace。运行只产生 `RESULTS_READY`、`NEED_CLARIFICATION`、`FALLBACK_REQUIRED`、`CANCELLED`、`FAILED` 等稳定终态。
7. Search Tool 只能调用 `SearchUseCase`。Runtime 要求回退时，由 Agent Server Adapter 调用同一个 Direct Search Use Case，返回 `AGENT_TO_DIRECT_FALLBACK`，不绕过 Search Context。
8. HTTP 接口继续使用 Spring MVC 同步 JSON。Agent 内部仅在命名、有界线程池中执行模型决策和 Tool；不向 Controller、Context Port 或领域对象暴露 `Mono`/`Flux`。
9. 首期使用可复现的 `DeterministicSearchLlmClient` 验证编排、追问、工具和 Trace，不把它宣称为真实大模型能力。后续真实模型只新增 `LlmClient` Adapter，不改 Runtime Core。

## 当前范围

Phase 1 已实现单进程同步请求中的有限步 Loop、Redis 执行权、PostgreSQL Session/Run 事件、Redis 热投影、取消入口、两个 AgentDef、Search Tool、Direct Fallback 与对照 Eval。

以下能力仍属于后续阶段：真实模型 Provider、Query Mode Router、多轮 `ConstraintPatch`、动态工具集与并行 Tool fan-out、跨实例取消信号、steer 先入队后取消、快照压缩与实例接管、事务 Outbox、SSE/流式 Push、HITL、子 Agent、Handoff、MCP、完整 OpenTelemetry 串联和故障注入。

## 后果

- Agent 运行机制能够独立测试和复用，Search 业务规则仍由 AgentOrchestration/Search Context 所有。
- Session 真相、执行过程和客户端进度有明确的数据职责，后续恢复与审计可以演进而不破坏 API。
- Redis 暂时承担执行权与热投影；PostgreSQL 保留事实源。多副本恢复正确性尚不能仅凭当前租约实现宣称完成。
- 当前决策结果是确定性的，适合学习和回归；它验证的是 Runtime 架构，不证明大模型理解效果。

