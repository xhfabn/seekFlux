# ADR-016：Core 内置默认 MCP Client 与可替换宿主策略

- 状态：Accepted
- 日期：2026-10-02
- 部分替代：[ADR-015](ADR-015-agent-runtime-publication-boundary.md) 的零第三方依赖与 MCP 私有 Adapter 边界；不改变已发布 RC1。

## 决策

1. 仍只维护 Core 和 Spring Boot Auto-configuration 两个公共模块。默认 MCP Client、连接管理、
   Tool Proxy、协议 DTO 与扩展接口从业务 Context 迁入 Core 的 `io.seekflux.platform.agentruntime.mcp`。
   Loop、Domain/Application 不直接依赖 MCP；远端工具仍通过普通 `AgentTool` 接入。
2. Core 保持 Java 21、无业务 Context/Spring/Redis/JDBC/Provider/Micrometer 绑定；允许受控
   Jackson JSON 依赖，仅 MCP 包可以引用 Jackson。HTTP 使用 JDK `java.net.http`。
   不为零依赖自行维护 JSON 解析器，也不要求消费者实现 JSON 编解码。
3. Host 是使用 Runtime 的应用及其连接/权限协调环境；默认 Client 连接外部 Server。
   `McpServerConfig` 是连接描述，不是 MCP Server 服务实现。本轮不增加对外提供工具的 Server。
4. 普通 Java 使用配置与 `McpConnectionManager(configs, registry)`；Spring 用户显式启用
   `seekflux.agent.mcp.enabled` 后自动装配，默认不连接、不发现、不开放远端工具。
   必须逐 Tool 指定本地 effect；注册策略默认继续拒绝 MUTATING。
5. 扩展接口为 `McpClientFactory/McpClient`、`McpCredentialProvider`、`McpToolAuthorizer`、
   `McpToolSchemaAdapter`、`McpToolResultAdapter` 和 `McpEventRecorder`。均有默认实现，
   Spring 用户提供同类型 Bean 即替换。附加授权不能绕过配置中的 tenant/user allowlist。
6. 凭据默认支持空引用和 `env:NAME` Bearer；缺失或非法引用失败关闭。结果默认映射标准 content/
   structuredContent/isError；`_meta.externalReceipt` 与 status=SUCCEEDED/FAILED 是可选的
   SeekFlux 约定，不是 MCP 通用保证。业务可替换参数映射、回执提取、状态查询参数与结果解释。
   默认调用参数不会自动注入幂等键；目标系统必须真的支持该约定，才能据此证明写操作可恢复。
7. 当前默认实现只覆盖 `2025-11-25` Streamable HTTP Tool 子集和有限 request-scoped SSE 响应；
   不支持 SSE 断点续传/GET 通知通道、STDIO、OAuth 协商、Resources/Prompts/Sampling/Elicitation。
   默认 Schema 只覆盖 bounded STRING/INTEGER/BOOLEAN/STRING_LIST，不是完整 JSON Schema。
   新版本/自定义传输可通过工厂替换；配置能描述并不代表默认 Client 支持，默认工厂明确拒绝。
8. 发现失败按 source 隔离；关闭后的 Manager 不再发现/重连；已取消调用不发送请求。
   JSON-RPC 响应校验版本与请求 ID；会话 404 标记失效，由后续调用重建会话，不立即重放失败调用。
   未知 MUTATING 结果继续走已有 ledger/reconciler，不因重连推断成功或再次执行写调用。
9. main 已快进合入发布分支；开发坐标统一为 `1.0.0-RC2-SNAPSHOT`。本轮不提交新实现、
   不推送、不创建 Tag、不上传或发布。RC1 的源 Tag 和公共制品保持不变。

## 后果与验证入口

- 普通使用者不重写协议；自定义策略必须保留限流、取消、版本冻结及未知结果不重放的不变量。
- 第三方业务 Server 的权限、认证、Schema 和写操作恢复仍需逐项验收。
- 固定协议服务与 fake Client 验证见 Core 的 `mcp` 测试；自动装配与 Bean 替换见 Auto-configuration 测试。
- 字节码依赖门槛见 [`verify-core-boundary.sh`](../../tools/agent-runtime/verify-core-boundary.sh)。
- API/SPI 契约见 [MCP 接入契约](../../contracts/runtime/agent-mcp-v1.md)；完成证据只记录在
  [Step 07](../learning/step-07-agent-reliability-platform.md)，当前总路线仍以[学习入口](../learning/README.md)为准。

## 2026-10-03：MCP 包职责分类

- 公共数据/配置放 `mcp.model`，扩展接口放 `mcp.spi`，连接治理放 `mcp.connection`，
  错误放 `mcp.exception`；默认实现按 HTTP、认证、Schema 与 Tool 放入 `mcp.infrastructure` 子包。
- `McpTranslatedTool` 抽为共享模型，避免 Schema SPI 返回默认实现拥有的嵌套类型；
  `McpToolCallGateway` 解耦 Proxy 与具体 Manager。保留默认结果适配常量作为便利绑定。
- Jackson 只允许 HTTP/Schema 实现引用，模型、SPI 签名与其他默认实现不暴露 Jackson。
- 只改变未发布 RC2 的 Java 包名与上述类型归属；Spring 配置键、协议、事件、取消、
  授权与副作用语义不变。RC1 无 MCP API，不需要旧包兼容层。
- 架构测试固定分包、契约签名与 Proxy/Schema 边界；具体命令与验收证据进入 Step 07。

## 协议依据

- [MCP 2025-11-25 架构](https://modelcontextprotocol.io/specification/2025-11-25/architecture)
- [Streamable HTTP](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)
