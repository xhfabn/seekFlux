# Agent Tool 模型定义契约 v1

适用未发布的 `1.0.0-RC2-SNAPSHOT` 源码，不修改 Maven Central RC1。

## SPI 与单一来源

- `AgentTool.description()` 是兼容 default 方法，返回空串；实现者提供工具用途、限制和返回含义。
- `AgentToolParameter` 增加末尾 `String description`；旧六参构造器和 factory 保留，缺失/null
  说明归一为空串，`withDescription` 返回新不可变参数。工具和参数说明上限均为 2048 字符。
- `AgentToolDefinition(name,description,schema)` 是 Registry 捕获的不可变模型定义。
  `definitionFor(name,version)` 获取当前/历史定义，`definitionsFor(names)` 从同一发布状态批量冻结。
- 参数定义不是某次调用参数；初始化配置/凭据不能作为描述发送给模型。Runtime 仍校验实际调用。

## 模型投影

`ChatToolDefinition.description` 使用真实用途，`inputSchema.properties.<name>.description` 使用
对应参数说明；空参数说明不生成该 JSON 字段。动态能力文本层从同一份冻结定义渲染，不再
使用占位描述。结构化 Tool 集仍严格等于当前 effectiveTools，inactive 组说明不增加可调用 Tool。

ToolGroup 文本包含 groupId/version、description、active 和成员；只有全部成员满足冻结
AgentDef 权限、注册可用范围及请求限制的组可显示。Schema 的 required/type/range/size 和
additionalProperties=false 语义保持不变。

## 版本与恢复

`DefinitionSnapshot.toolDefinitions` 是 `Map<String,AgentToolDefinition>`，非空时键集必须与
toolSchemaVersions 完全一致，name/schema.version 必须匹配。新 execution 始终写完整映射，
并经 `AgentDecisionContext.toolDefinitions` 传递；Scope 切换不丢失它。

恢复拒绝 Registry 中同版本不同定义，热更注册也拒绝同版本不同说明/Schema。旧 Snapshot
缺少该字段时归一为空映射并保留原版本恢复规则；旧参数 JSON 缺少 description 时归一为空串。
旧 Java 构造器均保留。完整冻结定义位于持久 `EXECUTION_METADATA`，Checkpoint 仍只存引用。

默认 MCP 的版本为固定 68 字符 `mcp-<sha256>`，按 JSON 数组编码的 configVersion、policyVersion、
remoteSchemaHash、工具 description、reconciliationSchemaHash 构造身份。参数说明已由原始
Schema hash 覆盖。旧开发版 MCP 版本不可自动映射到新版本；需要旧实现或失败关闭。
自定义 Schema Adapter 也必须为模型语义变化生成不同版本。

## 信任边界与不承诺

说明不能授予权限或决定 effect/审批；拒绝和副作用对账仍由本地策略执行。远端说明属于外部输入，
有长度限制但没有新增恶意指令分类器。旧实现空说明合法，不会自动生成用途。没有自动证明
自然语言与实现一致，也没有用固定请求验收冒充真实模型选择效果评测。

长期决定见 [ADR-018](../../docs/adr/ADR-018-tool-descriptions-and-frozen-model-definitions.md)。
