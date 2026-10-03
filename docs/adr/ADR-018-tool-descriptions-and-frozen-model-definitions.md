# ADR-018：工具说明的单一来源与模型定义冻结

- 状态：已接受
- 日期：2026-10-03
- 范围：未发布的 Runtime RC2、默认 MCP 与宿主 Search Tool；不修改已发布 RC1。

## 背景

`AgentTool` 和 `AgentToolParameter` 原先没有描述出口，Context Engine 只能向模型发送名称、
校验 Schema 和占位说明。MCP discovery 已保存 `McpTranslatedTool.description`，但 Proxy 没有
向通用 SPI 暴露它。ToolGroup 的用途同样未渲染。缺口不在是否使用工厂，而在通用定义不完整。

## 决策

1. 工具模块拥有名称、用途和参数语义；Agent Prompt/Skill 拥有任务策略，不复制工具说明。
   `AgentTool.description()` 默认空串以兼容旧实现；参数增加 description，保留六参构造器和
   原 factory，支持 `withDescription`。工具/参数说明 trim 后上限均为 2048 字符，缺失为空。
2. Registry 登记实例时捕获不可变 `AgentToolDefinition(name,description,schema)`，按名称和版本
   保存有限历史。source 替换中同版本不同定义拒绝，失败不发布部分状态；相同定义允许重连。
   这不是创建实例的工厂，也不是为每个 Agent 手写另一份说明。
3. execution 的 `DefinitionSnapshot.toolDefinitions` 保存完整模型可见定义，通过
   `AgentDecisionContext` 传给 Context Engine。模型的结构化 `tools` 和文本动态能力层使用同一
   定义；不再生成“SeekFlux Tool schema …”占位描述。工具参数约束和执行协议不改变。
4. 恢复时校验冻结定义与 Registry 中同版本定义一致，缺失/改动失败关闭。切换 ToolGroup 保留
   同一份工具定义，仅改变下一轮可用集合。旧 Snapshot 缺少 toolDefinitions 时仍可读取并沿用
   旧版本查找协议，但不能凭空还原它从未保存的说明；新快照不采用此兼容降级。
5. MCP Proxy 暴露 translated.description；Translator 保留受支持顶层参数的 description。
   默认版本为 `mcp-` + SHA-256，覆盖 configVersion、policyVersion、原始 input Schema hash、
   工具描述和 reconciliation Schema hash。长度固定 68，描述单独热更也成为新定义；
   remoteSchemaHash 仍只代表原始参数 Schema。自定义 Adapter 必须承担同样的版本责任。
6. Context 渲染冻结 ToolGroup 用途、active 状态与成员，只展示成员全部处于 AgentDef 权限、
   注册范围及请求限制内的组。inactive 组可供下一轮选择，但说明不授予执行权限。
   避免把全局 Catalog 中其他 Agent 的组直接展示给模型。
7. description 是模型输入，不是可信权限声明。effect、审批、身份限制、注册/执行策略与账本
   仍由宿主规则强制执行。MCP 远端文字不能越过这些规则；本轮不新增 Prompt 注入检测器。

## 升级与边界

- SearchDirect/SearchFiltered 与内置 SwitchToolGroups 的定义版本升到 v2，执行语义保持原样。
  本地工具改用途/参数说明必须升版本；执行中不得动态修改实例的定义。
- MCP 版本编码改变。旧部署遗留冻结版本只有在 Registry 仍保留相应旧实例、连接仍能验证同一
  版本时可恢复，否则失败关闭，不静默升级。部署前应先收敛旧执行，或明确提供旧版本兼容。
- 老 Tool 实现继续可注册，但空说明不会自动变成有意义的用途。消费者须主动提供说明。
- 不保证自然语言描述与任意实现代码自动一致，也不证明模型选择质量提升；宿主仍需契约测试
  和具体业务 Eval。未实现新工具工厂、注解解析器或在线版本制品仓库。
- 定义进入原有冻结元数据持久路径，不新增数据库表，不把历史消息重新内嵌进 Checkpoint。

## 验证与关联

Core 测试覆盖结构化/文本投影、组权限过滤、热更冲突和恢复防漂移；HTTP 固定模型服务验证
实际请求收到 MCP 描述及参数说明；JSON/真实 PostgreSQL 验证定义元数据往返与旧字段兼容。
完成证据见 [Step 07](../learning/step-07-agent-reliability-platform.md)。

- [工具定义契约](../../contracts/runtime/agent-tool-definition-v1.md)
- [MCP 接入契约](../../contracts/runtime/agent-mcp-v1.md)
- [ADR-013 能力作用域](ADR-013-agent-capability-snapshot-and-routing.md)
- [ADR-017 增量事实与状态基线](ADR-017-agent-event-facts-and-reference-snapshots.md)
