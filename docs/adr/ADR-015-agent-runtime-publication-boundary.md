# ADR-015：Agent Runtime 公共制品与宿主实现边界

- 状态：Accepted
- 日期：2026-09-26

> 历史发布决定：下面的零第三方依赖与 MCP 私有 Adapter 边界适用于已发布 RC1。
> 2026-10-02 的开发版本由 [ADR-016](ADR-016-default-mcp-in-runtime-core.md) 部分替代：
> Core 内置默认 MCP Client，允许受控 Jackson 依赖；两模块与宿主持久化/Provider 边界不变。

## 背景

Agent Runtime 主链与可靠性协议已经稳定，但原 `platform/agent-runtime` Maven 制品直接依赖
Spring Data Redis，PostgreSQL、OpenAI-compatible、MCP 和 Micrometer 实现又分散在 SeekFlux
宿主模块。直接发布该制品会把基础设施选择传递给消费者，也不能为 Spring Boot 消费者提供通用
装配入口。

目标不是发布数据库或模型集成套件，而是发布可复用执行内核及一个可选 Spring Boot 装配层。
Session、执行权、模型、恢复、分布式取消和业务 Tool 的实现责任仍由宿主承担。

## 决策

1. 对外运行时只发布两个 JAR：
   `io.github.xhfabn.seekflux:seekflux-agent-runtime-core:1.0.0-RC1` 和
   `io.github.xhfabn.seekflux:seekflux-agent-runtime-spring-boot-autoconfigure:1.0.0-RC1`。
2. Core 目标为 Java 21 和零第三方主依赖。它保存 Runtime API/SPI、领域模型、Loop、Router、Context、
   Tool、取消、恢复、等待、副作用和纯 Java 默认实现；不得引用 Spring、Redis、JDBC、Jackson、模型
   厂商、MCP 或 Micrometer。
3. Spring Boot Auto-configuration 只传递 Core。其 `spring-boot-autoconfigure` 和配置处理器依赖均为
   optional，因此不会为消费者引入 Spring Boot；宿主必须已经提供兼容的 Spring Boot 运行环境。
4. Auto-configuration 从宿主 Bean 组装 Registry、ContextEngine、Runtime、Loop、SessionExecutor、
   FeaturePipeline、Router 和有界执行器。`AgentSessionStore`、`ExecutionAuthorityStore` 与
   `PromptResolver` 是完整 Router 装配的必需端口；AgentDefinition/LlmClient 的选择仍属于宿主应用层。
5. 可选观测端口默认 NOOP。Recovery、分布式取消、持久摘要、Durable Wait、Steer Queue 与
   Mutating Tool 只有宿主提供对应可靠实现时才成立。默认 Tool 注册策略拒绝 `MUTATING`；宿主显式
   放开时仍必须满足副作用账本和 reconciliation 不变量。
6. Redis 实现移到 SeekFlux Agent Orchestration Infrastructure；JDBC/PostgreSQL、OpenAI-compatible、
   MCP 和 Micrometer 实现继续作为 SeekFlux 私有宿主 Adapter，不进入公开 Runtime 的传递依赖。
7. 两个发布 POM 不继承 SeekFlux monorepo 的 SNAPSHOT 父 POM，直接携带坐标、Java 版本、SCM、开发者
   和构建配置。`central-release` profile 负责 sources、Javadoc、GPG 和 Central Publisher Portal；
   GitHub Action 只做 staging，不自动 publish。
8. 发布由 `agent-runtime-v*` Tag 或手工 workflow 触发。根 `LICENSE` 是强制发布门槛；仓库所有者
   已于 2026-09-27 选择 Apache-2.0，两个公共 POM 使用相同许可证元数据，主 JAR 内携带
   `META-INF/LICENSE`。workflow 在许可证文件或元数据缺失时失败关闭。

## 后果

- 普通 Java 消费者只依赖 Core 并手工装配；已有 Spring Boot 的消费者只声明 Auto-configuration，
  Core 由其传递获得，Spring Boot 不由该依赖传递。
- Runtime 不再替消费者选择 PostgreSQL、Redis、模型厂商或 MCP，但消费者必须理解并实现 SPI 的
  fencing、幂等、原子提交、first-writer-wins 和未知写结果不重放等协议。
- 当前 `AgentSessionStore` 与 `AgentRecoveryStore` 仍是较宽的兼容 SPI；后续若按能力拆分，必须在正式
  `1.0.0` 前完成，或按语义化版本管理兼容演进。
- 当前 SeekFlux Agent Server 继续显式装配自己的生产 Adapter；公共 Auto-configuration 的回退语义
  由隔离 Spring Context 测试验证，不要求业务宿主立刻删除其定制组合根。

## 验证

- Core 136 个主源码文件无 JDK/SeekFlux 之外的 import，独立消费者依赖树只有
  `spring-boot-autoconfigure → core`，没有 Spring Boot、Redis、JDBC、Jackson、Provider 或 MCP 传递依赖。
- Auto-configuration 测试覆盖必需宿主端口装配、只读 Tool 收集、默认拒绝写 Tool、配置关闭和缺少
  必需端口时启动失败。
- `central-release` profile 已产出两个主 JAR、sources JAR 和 Javadoc JAR；
  2026-09-26 JDK 21 下全仓 `mvn -q test` 共 68 个报告、229 个测试，无失败、错误或跳过。
- 2026-09-27 已补齐 Apache-2.0 根许可证、两个公共 POM 的许可证元数据与发布包内许可证；
  JDK 21 下两个目标模块测试、`central-release` 打包通过，四个主/源码 JAR 均含
  `META-INF/LICENSE`。同日发布前复核：Portal 命名空间 Verified，四个 GitHub Secret 名称
  可见，公钥已可检索；当时 Secret 值、CI 签名及实际 staging/publish 尚未验证。
- 2026-09-27 Tag `agent-runtime-v1.0.0-RC1` 触发的
  [GitHub Actions 运行](https://github.com/xhfabn/seekFlux/actions/runs/36301443634) 已通过签名与
  Central 上传；Portal deployment `c65b92a7-cd6f-41f4-8a20-f71cbbd6c927` 已 `VALIDATED`
  （2/2 组件）。最终人工 Publish 与公开仓库消费者验收仍未完成。
- 同日仓库所有者确认后人工 Publish；两个 POM 已从 Maven Central 公共仓库返回 HTTP 200，
  仓库外空缓存 Spring Boot 消费者编译成功，依赖树确认 Auto-configuration 只传递 Core。
  Portal deployment `c65b92a7-cd6f-41f4-8a20-f71cbbd6c927` 最终显示 `PUBLISHED`。

## 关联

- [Agent Runtime Core 使用说明](../../platform/agent-runtime/README.md)
- [Spring Boot Auto-configuration 使用说明](../../platform/agent-runtime-spring-boot-autoconfigure/README.md)
- [Agent Runtime 内核说明](../agent-runtime.md)
- [模块边界](../module-boundaries.md)
- [ADR-004：自研 Agent Runtime](ADR-004-ark-leto-inspired-agent-runtime.md)
