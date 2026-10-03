# ADR-004：参考 Ark-Leto 主链路自研 Agent Runtime

- 状态：Accepted
- 日期：2026-08-08
- 最近更新：2026-09-26

## 背景

项目需要在 Direct Search 之上建立可复用 Agent Runtime。参考文档《Ark-Leto 框架内核 与 Agentspark 主链路 原理详解》给出了 Router、FeaturePipeline、SessionExecutor、AgentLoop、WorkspaceEvent、执行权和 Tool 调度之间的关键关系，但项目没有 Ark-Leto 源码，也没有把它作为可验证的运行时依赖。

如果直接把文档中的类名复制成 Search 专用流程，Runtime 会与业务语义耦合；如果只实现一个 Controller 内循环，又会丢失会话一致性、幂等、恢复和可观测边界。因此需要吸收其主链路与不变量，同时按 SeekFlux 的六边形模块边界自行实现。

## 决策

1. `platform/agent-runtime` 是自研、业务无关的 Runtime 模块，不依赖 Search Context、模型厂商协议或 Elasticsearch 业务访问。其 Domain/Application 保持普通 Java，Spring Data Redis 等 Runtime 默认技术实现只允许进入 Infrastructure。
2. Runtime 内部固定采用 DDD/Ports-and-Adapters 分层。`application` 只作为对外契约面，包含业务调用的 `api`、输入 `command`、业务可定制的 `spi/business` 和运行能力 `spi/capability`，契约专属 DTO 就近放在对应子树，不设置 `application/model`、`application/port` 或 `application/service`。领域模型按 `agent/definition`、`decision`、`run`、`session`、`tool`、`feature`、`execution` 组织；各组件关系与主链编排统一放在 `domain/service` 的 runtime、router、execution、loop、feature、context、tool、shadow 子域中。`infrastructure` 提供 Runtime 拥有的纯 Java 和 Redis 可替换默认实现；模型厂商协议由使用 Runtime 的 Context Infrastructure 适配。
3. 主链路固定为 `Router → FeaturePipeline → SessionExecutor → AgentLoop`。FeatureNode 使用显式列表和稳定顺序，当前内置节点依次为 SessionLoad、AgentResolve、ParamInit、ResumeEval。
4. 同一 Session 必须先获得执行权，再提交本轮 UserMessage。执行权由 Port 抽象，Redis Adapter 使用带 owner 比较的获取、续租和释放；重复 `requestId` 不再次进入 Loop。
5. PostgreSQL 中的 `WorkspaceEvent` 是 Session 的追加式事实源；User、Assistant 和 ToolResult 都使用版本化消息 Envelope、稳定 message/call ID 与 set-once position。Tool Call Assistant 必须先于且唯一对应一个 ToolResult；Assistant 正文、reasoning、tool calls 和 ToolResult 的 raw/model/display/structured/resources 分字段保存。AgentRun/RunEvent 是独立执行轨迹，前端过程 `PushEvent` 不参与 Session 投影，三类事件不能互相替代。
6. `AgentLoop` 每轮都通过 `ContextEngine` 组装上下文，通过厂商无关 `LlmClient` 获取结构化 Decision，通过 Tool Registry/Executor 执行受 Schema、次数和共同 Deadline 限制的工具调用。
7. AgentDef、Prompt、决策提供方和 Tool Schema 版本在运行开始时冻结并进入 Trace。运行只产生 `RESULTS_READY`、`NEED_CLARIFICATION`、`FALLBACK_REQUIRED`、`CANCELLED`、`FAILED` 等稳定终态。
8. Search Tool 只能调用 `SearchUseCase`。Runtime 要求回退时，由 Agent Orchestration Infrastructure Adapter 调用同一个 Direct Search Use Case，返回 `AGENT_TO_DIRECT_FALLBACK`，不绕过 Search Context。
9. HTTP 接口继续使用 Spring MVC 同步 JSON。Agent 内部仅在命名、有界线程池中执行模型决策和 Tool；不向 Controller、Context Port 或领域对象暴露 `Mono`/`Flux`。
10. 首期使用可复现的 `DeterministicSearchLlmClient` 验证编排、追问、工具和 Trace，不把它宣称为真实大模型能力。后续真实模型只新增 `LlmClient` Adapter，不改 Runtime Core。
11. 技术和业务适配器不由进程宿主持有。2026-09-26 发布 RC1 时 Core 收缩为零第三方主依赖，宿主 Adapter 边界见 [ADR-015](ADR-015-agent-runtime-publication-boundary.md)。2026-10-02 开发版本把通用 MCP Client 与连接管理迁入 Core，允许 MCP 包使用 Jackson，决策见 [ADR-016](ADR-016-default-mcp-in-runtime-core.md)。Redis、Search Agent 映射/Tool、Direct Search、投影、Provider 和指标仍由业务 Context 持有。Spring Boot 通用装配保持独立、可选且不传递 Spring Boot；`apps/agent-server` 仍只保留 `interfaces/rest`、启动与最终组合装配。
12. Context 采用显式 Layer 和无状态 Renderer；完整消息与 Tool Schema 一起计量。长历史只能按完整 turn 以版本化摘要和 inclusive cutoff 压缩，禁止没有覆盖摘要的硬截断。摘要先写 PostgreSQL 共享事实；当前不设置热投影，未来如增加只能在事实提交后更新。
13. 同步 Provider 只有在任何模型输出产生前的 400/413 上下文溢出才允许强压缩有界重试；结构化输出格式错误由有限 OutputGuard repair/degrade/fail 处理并响应同一取消 token。内容安全是独立业务策略，不由格式 Guard 冒充。

## Phase 1 范围与后续演进

Phase 1 已实现单进程同步请求中的有限步 Loop、Redis 执行权、PostgreSQL Session/Run 事件、Redis 热投影、取消入口、两个 AgentDef、Search Tool、Direct Fallback 与对照 Eval。

Phase 2 后续完成了 Provider Adapter、Query Mode Router、多轮 `ConstraintPatch`、动态工具集和并行 Tool fan-out，具体决策见 [ADR-005](ADR-005-complex-search-agent-routing-and-state.md)。Phase 3 又完成 fencing、失主接管、跨实例取消、事务 Outbox、故障注入、Shadow 和成本计量，具体决策与 Ark-Leto 反向核对见 [ADR-006](ADR-006-agent-reliability-fencing-outbox-shadow.md)。2026-09-13～16 继续补齐 Assistant/ToolResult 完整历史、Checkpoint/pending Tool、写副作用账本、Steer Queue/Drain，以及上下文预算/压缩、400/413 重试和 OutputGuard。仍未完成的是 SSE/流式 Push、HITL、子 Agent、Handoff、MCP 和完整 OpenTelemetry 串联。

## 后果

- Agent 运行机制能够独立测试和复用，Search 业务规则仍由 AgentOrchestration/Search Context 所有。
- Runtime 的领域子模型、领域编排服务、API、Command、业务 SPI、能力 SPI 和默认基础设施 Adapter 具有可见的物理边界；新增实现应落入对应概念，不能重新堆回根包。Domain 可以依赖 Application 中的稳定契约，但不能依赖具体 Infrastructure。
- 业务接入方既可以调用 Runtime API，也可以实现、替换或装饰 Runtime SPI；默认实现不是业务必须接受的固定行为。
- Runtime Core 与 Java 默认实现内聚在公共 Maven 制品；通用 MCP 默认 Adapter 在 Core 内，JSON 依赖限于 MCP 包；Spring Boot 自动装配是独立薄层。Redis、持久化、Provider 和观测 Adapter 由宿主拥有且不随公共 Runtime 发布。包级依赖规则保证 Domain/Application 不引用 MCP 或宿主 Infrastructure，可部署 Server 只负责接口与最终装配。
- Session 真相、执行过程和客户端进度有明确的数据职责，后续恢复与审计可以演进而不破坏 API。
- 一轮 Assistant/ToolResult 与终态仍在同一 fencing 事务提交，读取方不会观察到孤立 ToolResult 或半轮消息；提交前的模型/Tool 进度由独立 Checkpoint 和 journal 恢复，已完成 Tool 不再要求整轮重跑。两类恢复事实只有在 Outcome/Outbox 同事务成功后才清理。
- Redis 承担执行权、取消信号、Shadow 开关与热投影；PostgreSQL 保留事实源。多副本恢复正确性由 fencing、强一致重放、事务 Outbox 和故障测试共同保证，而不是只依赖租约。
- 默认决策结果是确定性的，适合学习和回归；OpenAI-compatible Adapter 的存在仍不等于已经证明真实大模型理解效果。
- 确定性 Skeleton 摘要保证 cutoff 与 Tool 语义完整，代价是摘要质量不等同于模型摘要；当前直接强读 PostgreSQL 避免热投影一致性问题，但长会话极高吞吐下的读扩展需另行评估。
