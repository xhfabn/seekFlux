# Step 0：工程基线

> 本文按序记录最初建立仓库骨架时的历史切片。它对应开发 Step 0，不是 [`SeekFlux.md`](../../SeekFlux.md) 中的 Agent Phase 0。当前项目已经完成到 Step 3，真实下一步见[学习路线首页](README.md)。

## 本阶段状态

- 状态：已完成
- 范围：当时的工程骨架，不包含可运行的业务 API
- Agent 映射：Agent 前置工程能力，不代表 Agent 已实现
- 对应决策：[`ADR-001`](../adr/ADR-001-modular-monolith.md)

## 完成了什么

本阶段把架构设计转换成了可以继续编码的仓库边界：

- 根 `pom.xml` 管理 Java 21、Spring Boot、Flink、JUnit 和 Maven 插件版本；
- `contexts/` 建立九个业务限界上下文，并预留六边形分层；
- `platform/` 建立检索、持久化、消息、模型服务和可观测 Adapter 模块；
- `apps/` 区分在线服务、内容控制面、异步 Worker 和可选的 Python 训练入口；
- `pipelines/realtime-features` 建立 Flink 实时任务模块；
- `contracts/` 放置 OpenAPI、事件 Envelope 和特征契约的初始版本；
- `deploy/` 提供本地中间件和 Compose 配置；
- `.tool-versions` 固定 JDK、Maven 和 Python 基线。

这里列出的是 Step 0 完成时的状态，不应用来判断今天的功能进度。当前已有 Search、Feed、画像和内容链路；尚未创建规划中的第十个 `AgentOrchestration` Context、`apps/agent-server` 和 `platform/agent-runtime`。目录中的 `.gitkeep` 或空模块只代表边界预留，不代表能力已经实现。

## 架构上学什么

### 1. 聚合模块与可执行模块不同

根工程、`contexts`、`platform`、`apps` 和 `pipelines` 的 POM 使用 `packaging=pom`，作用是组织构建；`online-server` 等子模块才会逐步成为可执行或可依赖的 JAR。

### 2. 逻辑边界与部署边界不同

当前九个 Context 是逻辑业务边界，但首期不会部署成九个微服务。多个 Context 可以被 `online-server` 装配到同一进程，同时仍禁止共享可变实体和越界访问数据库。未来的 `AgentOrchestration` 是第十个业务 Context；Agent Runtime 属于 Platform，不应被误列为第十一个 Context。

### 3. 六边形架构控制依赖方向

领域层描述业务规则；Application 调用输入/输出 Port；Adapter 负责 HTTP、数据库、Kafka、Elasticsearch 等细节。这样单元测试领域规则时不需要启动中间件。

### 4. 契约先确定协作形状

`contracts/openapi` 定义同步 API 的初始形状，`contracts/events` 定义异步 Envelope，`contracts/features` 预留特征定义。契约不是一次定死，但后续必须显式版本化并兼容演进。

### 5. 为什么 Java 与 Python 共存

低延迟在线服务、Worker、未来 Agent Runtime 和 Flink Job 使用 Java；训练及部分算法实验可以使用 Python。Direct Search 与 Agent Eval 的格式属于版本化评测契约，不因使用哪种语言而改变。Python 是后置训练/实验工具，不是开始 Agent 的前置条件。

## 关键文件入口

| 文件 | 阅读目的 |
| --- | --- |
| [`pom.xml`](../../pom.xml) | 理解 Maven Reactor 和统一版本管理 |
| [`docs/module-boundaries.md`](../module-boundaries.md) | 理解允许和禁止的依赖方向 |
| [`apps/online-server/pom.xml`](../../apps/online-server/pom.xml) | 理解应用如何装配 Context 与 Platform |
| [`apps/training-runner/pyproject.toml`](../../apps/training-runner/pyproject.toml) | 理解 Python 训练环境边界 |
| [`contracts/openapi/seekflux-v1.yaml`](../../contracts/openapi/seekflux-v1.yaml) | 理解计划提供的首批 API |
| [`deploy/compose/compose.yml`](../../deploy/compose/compose.yml) | 理解本地中间件拓扑 |

## 验证方式

在项目根目录执行：

```bash
mvn validate
docker compose --env-file .env.example -f deploy/compose/compose.yml config --quiet
uv run --project apps/training-runner python --version
```

预期结果：Maven Reactor 配置有效、Compose 配置能够展开、训练侧使用 Python 3.12。基础设施的实际启动与连通性需要额外执行 `deploy/local/infra.sh status` 或 Compose 启动命令验证。

## 可以动手做的练习

1. 从 `apps/online-server/pom.xml` 选择一个 Context，画出未来的输入 Port 和输出 Port，不写中间件代码。
2. 解释为什么 `Content` 和 `Search` 不能共享一个 JPA Entity，即便首期在同一进程中。
3. 为“内容画像已发布”设计一个事件 Payload，并说明 Envelope 中哪些字段用于幂等、追踪和版本兼容。
4. 假设 Elasticsearch 不可用，指出降级策略属于 Search/Recommendation 规则还是 retrieval Adapter，并说明理由。

## 当时的下一步与当前路线

Step 0 完成后，当时的下一步是 Step 1“内容登记与画像发布”，该切片现在已经完成。项目随后也已完成 Step 2 关键词搜索和 Step 3 Feed 基线。

当前不要从本文最后一段推断下一任务；真实阶段和下一步始终以[学习路线首页](README.md)为准。
