# ADR-014：MCP Tool 来源、信任边界与恢复语义

- 状态：Accepted
- 日期：2026-09-19

> 2026-10-02：协议适配器的模块归属与可替换业务映射由
> [ADR-016](ADR-016-default-mcp-in-runtime-core.md) 更新为公开 Core 默认实现。
> 本 ADR 的信任边界、source 隔离、版本冻结与 UNKNOWN 写结果不重放约束继续有效。

## 背景

AR-9A 已经把 AgentDef、Skill、ToolGroup 和 Tool Schema 冻结进 execution 快照，但原有
`AgentToolRegistry` 只适合启动时注册的本地 Tool。MCP server 是独立故障域，远端名称、Schema、
副作用声明、鉴权和输出都不能直接成为 Runtime 权限事实；断线、重连或 Schema 热更也不能让同一
execution 静默改用新定义。对于写 Tool，网络失败尤其不能以“重连后再调用一次”处理。

## 决策

1. 第一版只实现 MCP `2025-11-25` 的 Streamable HTTP Tool 子集：`initialize`、
   `notifications/initialized`、分页 `tools/list`、`tools/call`、request-scoped SSE/JSON 响应、
   Session 终止和 `notifications/cancelled`。Resources、Prompts、Sampling、Elicitation、server push
   以及其他 transport 不进入本阶段。协议适配器保持在 Agent Orchestration Infrastructure，Loop
   仍只依赖普通 `AgentTool`。
2. Server 配置冻结 `serverId/configVersion/endpoint/credentialRef/protocolVersion`、连接与请求
   超时、每 server 并发、熔断窗口、最大响应和 Tool 本地策略。凭据只保存 `env:NAME` 引用，请求
   时即时解析为 Bearer header；密钥值不进入配置对象、Workspace、Checkpoint、Trace、事件或日志。
3. 发现后的名称固定为 `{serverId}__{remoteToolName}`，来源固定为 `mcp:{serverId}`。
   `AgentToolRegistry` 按 source 原子替换或批量注销；跨来源同名拒绝，失败不会删除本地或其他
   server 的 Tool。注册表保存有限个旧 Schema 版本供在途执行和 Checkpoint 校验，但旧代理只有在
   当前连接仍提供同一冻结版本时才能调用，否则以 `MCP_TOOL_VERSION_UNAVAILABLE` 失败关闭。
4. 远端 JSON Schema 先经过本地白名单翻译：根必须为 object，只支持有界 string/integer/boolean/
   string-array，限制总字节、深度、节点、属性数、字符串和数组长度。effect、审批、tenant/user
   allowlist 和 reconciliation Tool 全部来自本地版本化策略，远端声明不能把写操作降级为只读。
   Agent API 接收网关认证后的 `X-Tenant-Id/X-User-Id` 并传播到 ToolContext，但这些身份字段不渲染
   给模型。
5. `CapabilitySnapshot` 升级到 Schema v2，把 execution 开始时实际注册的 Tool 集纳入指纹；
   RunDefinition 同时冻结由 server config version、本地 policy version、远端 Schema hash 和可选
   reconciliation Schema hash 组成的 Tool Schema 版本。Schema v1 快照仍按旧指纹算法恢复，避免
   AR-9A 已保存 Checkpoint 因字段新增失效。
6. `McpConnectionManager` 每 server 单飞发现、懒重连、请求 Deadline、Semaphore bulkhead、连续
   建连失败熔断、受控退役旧 client 和响应读取上限。Runtime CancellationToken 是本地权威：取消
   HTTP Future，并尽力向 peer 发送 `notifications/cancelled`；远端未确认不改变本地取消终态。
7. `MUTATING` MCP Tool 继续走 AR-4 的持久 ledger。外部调用异常留下 `UNKNOWN`，恢复不重放原写
   请求；只有显式配置且 Schema 接受 `idempotencyKey/toolCallId` 的只读 status Tool 才能做
   reconciliation，否则保持 UNKNOWN。远端回执只从 `_meta.externalReceipt` 提取，不信任任意输出
   作为本地账本结论。
8. Actuator health 暴露受控 server 状态、config version、当前 Tool Schema version、在途量和熔断
   截止时间。生命周期与调用观测只记录受控 server/tool/outcome/reason，远端错误文本和凭据不进入
   Metrics 标签。事件形状由 `agent-mcp-lifecycle-v1` 固定。

## 后果

- 本地 Tool、不同 MCP server 与失效 MCP server 相互隔离；新 execution 只看到当时成功注册的
  Tool，断线 source 不会残留在模型上下文。
- Schema 热更会为新 execution 生成新版本；旧 execution 不会静默套用新 Schema。跨进程恢复仍需
  目标部署能发现同一版本，否则按设计失败关闭。
- 当前配置通过 Spring 启动装配，关闭时终止 Session；没有在线配置发布、OAuth 协商或管理端强制
  refresh API。新增实际 server 时仍需为其 endpoint、凭据责任人、effect、allowlist、审批和写操作
  对账能力单独验收。
- 自研小型协议 Adapter 是有意选择：当前只需要 Tool 子集和 Runtime 取消语义。未来若官方 Java SDK
  完整覆盖所需协议版本、取消和响应上限，可替换 transport 实现，但本 ADR 的本地信任边界、冻结
  版本和副作用规则不变。

## 验证

- 协议级本机 Streamable HTTP server 验证初始化、Session header、SSE 发现、JSON 调用、取消通知
  和超大响应中止；受控 `McpClient` fake 验证断线/重连、source 批量注销、Schema 热更、名称冲突、
  tenant/user 拒绝、bulkhead、熔断和 UNKNOWN 写结果。
- 全仓 `mvn -q test` 共 67 个报告、224 个测试，无失败、错误或跳过。

## 关联

- [Agent Runtime 路线](../../platform/agent-runtime/ROADMAP.md)
- [Agent Runtime 说明](../agent-runtime.md)
- [ADR-006：Agent 多实例可靠性](ADR-006-agent-reliability-fencing-outbox-shadow.md)
- [ADR-013：能力快照与 ToolGroup](ADR-013-agent-capability-snapshot-and-routing.md)
- [MCP lifecycle contract](../../contracts/events/agent-mcp-lifecycle-v1.schema.json)
- [OpenAPI](../../contracts/openapi/seekflux-v1.yaml)
