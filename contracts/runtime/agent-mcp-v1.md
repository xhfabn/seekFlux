# Agent MCP 公共接入契约 v1

适用代码：`1.0.0-RC2-SNAPSHOT` 的 `io.seekflux.platform.agentruntime.mcp`。本开发版本尚未发布；
已公开 RC1 没有这些 MCP 类。[ADR-016](../../docs/adr/ADR-016-default-mcp-in-runtime-core.md)
定义模块边界；[`agent-mcp-lifecycle-v1`](../events/agent-mcp-lifecycle-v1.schema.json) 的事件形状不变。

## 普通接入

- Host 提供 `AgentToolRegistry`，列出 `McpServerConfig` 与明确的 `McpToolPolicy`。
- `McpConnectionManager(configs, registry)` 默认使用 Streamable HTTP Client、受限 Schema Translator、
  环境凭据提供器、标准结果映射与 NOOP 事件记录器。`start()` 发现并注册允许的工具；
  `close()` 原子禁止后续发现/调用，并注销来源；`health()` 提供受控状态。
- 本地工具名 `{serverId}__{remoteName}`，source `mcp:{serverId}`；冲突拒绝，断线摘除单一 source。
- 公共 Spring 属性前缀 `seekflux.agent.mcp`，默认关闭；启用且存在 Registry 时装配。
  每个工具必须显式配置 effect；不依据远端 annotations 放宽权限。

## 可替换接口

公共类型的包归属（均以 `io.seekflux.platform.agentruntime.mcp` 为前缀）：

| 包 | 类型与职责 |
| --- | --- |
| `model` | `McpServerConfig`、`McpToolPolicy`、`McpRemoteTool`、`McpCallResult`、`McpEvent`、`McpTranslatedTool` |
| `spi` | 下列扩展接口及 `McpToolCallGateway` 调用/策略拒绝观测契约 |
| `connection` | `McpConnectionManager`：默认发现、连接治理及关闭入口 |
| `exception` | `McpException`：受控错误与连接失败分类 |
| `infrastructure.http` | `StreamableHttpMcpClient`、`StreamableHttpMcpClientFactory` |
| `infrastructure.auth` | `EnvironmentMcpCredentialProvider` |
| `infrastructure.schema` | `McpSchemaTranslator` |
| `infrastructure.tool` | `McpProxyTool`、`DefaultMcpToolResultAdapter` |

2026-10-03 的开发版包分类直接更新所有仓库调用方；RC2 尚未公开，因此不保留旧扁平包别名。
`McpToolSchemaAdapter.translate` 返回独立 `model.McpTranslatedTool`，不依赖默认转换器的嵌套类型。
Proxy 通过 `spi.McpToolCallGateway` 调用 Manager；普通宿主仍只配置或替换已有扩展接口。

| 接口 | 默认 | 定制责任 |
| --- | --- | --- |
| `McpClientFactory` / `McpClient` | JDK HTTP + Jackson，2025-11-25 Tool 子集 | 新传输/协议或 SDK；保持取消、响应限制和不盲目重试 |
| `McpCredentialProvider` | 空引用或 env:NAME Bearer，即时解析 | 认证来源/动态 Token；不得把秘密写入 Runtime 状态 |
| `McpToolAuthorizer` | 不添加额外限制，但始终执行配置 allowlist | 附加权限；不能绕过本地 allowlist |
| `McpToolSchemaAdapter` | bounded 简单字段 Schema | 转换为 Runtime 可支持的 Schema；版本必须覆盖语义，不静默丢弃约束 |
| `McpToolResultAdapter` | 标准内容与可选 SeekFlux 回执/状态约定 | 调用参数映射、回执提取、对账参数/状态解释；未知仍返回 UNKNOWN |
| `McpEventRecorder` | NOOP | 指标/Trace；异常不能改变调用结果 |

`McpToolResultAdapter.DEFAULT` 保留默认实现的便利入口，其签名不暴露具体实现或 Jackson。

Spring 提供对应 Bean 时默认实现回退。Manager 也可整体替换；提供 Client 工厂不需要重写 Proxy 或 Loop。
自定义 Schema/授权/结果策略发生语义变化时必须同步提升本地 `policyVersion`/`configVersion`，
否则运行恢复不能证明使用了同一策略。

## 失败与副作用

- 发现失败不拖垮其他来源；缺失凭据、Schema 拒绝或注册策略拒绝会留下 DISCONNECTED。
- 冻结版本不可用返回 `MCP_TOOL_VERSION_UNAVAILABLE`；关闭后返回 `MCP_MANAGER_CLOSED`。
- 本地取消不等待远端确认；取消前不发送请求，在途取消中止 Future 并尽力通知 peer。
- HTTP 404 + Session 视为会话失效，后续调用可重建；失败调用不会在 Client 内自动重放。
- 默认对账工具参数为 idempotencyKey/toolCallId；默认业务状态约定为 structuredContent.status。
  缺少对账工具、远端错误、未知状态保持 UNKNOWN。自定义参数映射会同时用于发现时的 Schema 探针
  与真实对账；探针使用占位字符串，不执行外部调用。
- `_meta.externalReceipt` 仅为可选回执约定；没有回执不证明写操作失败或未执行。
  工具重试安全性和外部幂等语义不能由 MCP 协议本身保证。

## 明确不承诺

完整 MCP、完整 JSON Schema、OAuth、STDIO、SSE 持久通知/断点续传、对外 Server、在线配置发布，
以及任意第三方写工具的幂等/对账能力。扩展接口存在不等于这些能力已经默认实现。
