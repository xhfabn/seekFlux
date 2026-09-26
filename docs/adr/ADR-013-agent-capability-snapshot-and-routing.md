# ADR-013：Agent 能力快照、作用域与 ToolGroup 路由

- 状态：Accepted
- 日期：2026-09-18

## 背景

AR-1～AR-8B 已经建立可靠的 Session、Loop、Checkpoint、Context 和 Tool 安全边界，但动态
工具集仍主要依赖请求属性约定，不能表达跨 turn 的 Skill 激活、请求级临时 Skill、同名 shadow、
ToolGroup 切换和恢复时的版本冻结。如果每个模型轮都直接读取最新配置，热更或崩溃恢复会让同一
execution 看到不同指令与 Tool Schema；如果只相信模型输出，又可能越过 AgentDef 的最大权限。

## 决策

1. `AgentDefinition.allowedTools/skillRefs` 是最大权限边界。全局 `CapabilityCatalog` 只提供
   AgentDef 可引用的版本化 `SkillDefinition` 和 `ToolGroupDefinition`，不能扩大该边界；Catalog
   加载时拒绝重复 ID、未知 Tool 和非法引用。
2. 能力有三种作用域：全局 Catalog、由 `CAPABILITIES_CHANGED` Workspace 事件投影的 Session
   持久激活，以及只存在于一个 request/execution 的 ephemeral Skill 和 Tool 限制。请求级 Skill
   必须声明 `REQUEST` 来源、Schema/version 和大小上限；同 ID 时显式替换全局定义，不合并指令
   或 required Tool，避免 shadow 泄漏。
3. FeaturePipeline 在 Session/Agent 解析后、参数初始化前生成不可变 `CapabilitySnapshot`。它冻结
   Catalog hash、Skill/ToolGroup 定义及版本、AgentDef 上界、请求限制、激活集合和最终 Tool 集；
   快照指纹覆盖冻结 Skill/ToolGroup 定义并随激活集合变化，进入 Trace 与 Runtime Checkpoint；
   反序列化时还会重算 effective Tool、active instruction 和 lazy summary，拒绝内容损坏的快照。
4. 恢复只接受当前 Runtime 可验证的冻结 Catalog 版本/hash 和 Tool Schema 版本。缺失版本返回
   `CAPABILITY_SNAPSHOT_UNAVAILABLE`，不静默套用新配置。已经运行的 execution 始终使用自身
   快照；新配置只影响新 execution。部署时如需跨版本恢复，必须同时保留被引用的旧 Catalog。
5. Session 持久变更只能走版本化 Control API。数据库以 Session 行锁、`capability_version`、
   `operationId` 唯一索引和完整变更内容实现 optimistic concurrency 与严格幂等；同 ID 同内容返回
   原结果，同 ID 不同内容冲突，EXECUTING/SUSPENDED Session 不接受新变更；`COMPLETED` 表示
   上一 turn 已收敛、Session 仍可继续，因此是允许修改的安全边界。
6. `switch_tool_groups` 是 `READ_ONLY` 内置控制 Tool，只接受冻结 Catalog 中的 group。它的结果在
   当前 Tool batch 完成后写入 Runtime 快照，仅改变下一模型轮，不撤回已派发调用。`alwaysActive`
   控制组不受业务 Tool 请求级缩减影响；普通组、未归组 Tool 和请求限制遵循确定规则。
7. Tool 暴露与执行是两道防线：每轮由快照生成 Tool Schema；实际调用仍经过注册表、参数 Schema、
   `ToolExecutionPolicy`、Deadline、取消和副作用账本。模型伪造名称或已失效权限不能因曾经暴露而
   获得执行权。
8. Context 分开渲染 ephemeral 指令、持久 active Skill 指令和 lazy Skill 摘要，Tool Schema 继续
   进入 AR-6 Token 预算。Trace、RunEvent、Push 和 Micrometer 记录受控版本/枚举及数量；用户
   注入的 Skill ID、指令和错误文本不作为 Metric 标签。

## 后果

- Search Agent 的宽搜、精确搜索和能力控制工具现在由同一份冻结快照驱动，模型上下文与 Runtime
  实际允许集一致；确定性 Provider 也不再读取旧的 `attributes.allowedTools`。
- Session 激活可跨 turn 重放，Queue/Drain 会按被提升请求和最新 Session 投影重新解析；ephemeral
  Skill 会进入当次 Checkpoint，但不会进入 Session 激活投影。
- Catalog 当前由宿主静态装配，没有提供在线编辑器、分布式配置发布或旧版本制品仓库；这些属于
  具体配置平台。请求级 Skill 只允许有界、可序列化字段，调用方不得把凭据写入 instruction。
- MCP 后续按 [ADR-014](ADR-014-mcp-tool-source-and-trust-boundary.md) 作为独立 Tool 来源接入，
  并把 execution 开始时实际注册的 Tool 集纳入 v2 快照；Chained/Graph 仍是独立编排引擎。

## 关联

- [Agent Runtime 路线](../../platform/agent-runtime/ROADMAP.md)
- [Agent Runtime 说明](../agent-runtime.md)
- [Agent capability lifecycle v1](../../contracts/events/agent-capability-lifecycle-v1.schema.json)
- [OpenAPI](../../contracts/openapi/seekflux-v1.yaml)
