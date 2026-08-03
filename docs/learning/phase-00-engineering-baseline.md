# Phase 0：工程基线

## 本阶段状态

- 状态：已完成
- 范围：工程骨架，不包含可运行的业务 API
- 对应决策：[`ADR-001`](../adr/ADR-001-modular-monolith.md)

## 完成了什么

本阶段把架构设计转换成了可以继续编码的仓库边界：

- 根 `pom.xml` 管理 Java 21、Spring Boot、Flink、JUnit 和 Maven 插件版本；
- `contexts/` 建立九个业务限界上下文，并预留六边形分层；
- `platform/` 建立检索、持久化、消息、模型服务和可观测 Adapter 模块；
- `apps/` 区分在线服务、内容控制面、异步 Worker 和 Python 训练入口；
- `pipelines/realtime-features` 建立 Flink 实时任务模块；
- `contracts/` 放置 OpenAPI、事件 Envelope 和特征契约的初始版本；
- `deploy/` 提供本地中间件和 Compose 配置；
- `.tool-versions` 固定 JDK、Maven 和 Python 基线。

当前目录中的 `.gitkeep` 只代表边界已经预留，不代表对应功能已经实现。README 中列出的 Search、Feed 等是计划装配职责，不是当前可调用能力。

## 架构上学什么

### 1. 聚合模块与可执行模块不同

根工程、`contexts`、`platform`、`apps` 和 `pipelines` 的 POM 使用 `packaging=pom`，作用是组织构建；`online-server` 等子模块才会逐步成为可执行或可依赖的 JAR。

### 2. 逻辑边界与部署边界不同

九个 Context 是逻辑业务边界，但首期不会部署成九个微服务。多个 Context 可以被 `online-server` 装配到同一进程，同时仍禁止共享可变实体和越界访问数据库。

### 3. 六边形架构控制依赖方向

领域层描述业务规则；Application 调用输入/输出 Port；Adapter 负责 HTTP、数据库、Kafka、Elasticsearch 等细节。这样单元测试领域规则时不需要启动中间件。

### 4. 契约先确定协作形状

`contracts/openapi` 定义同步 API 的初始形状，`contracts/events` 定义异步 Envelope，`contracts/features` 预留特征定义。契约不是一次定死，但后续必须显式版本化并兼容演进。

### 5. 为什么 Java 与 Python 共存

低延迟在线服务、Worker 和 Flink Job 使用 Java；训练、评测和算法生态使用 Python。模型和特征版本是两侧的协作边界，不能通过复制临时代码保持一致。

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

## 下一步

实现 Step 1“内容登记与画像发布”纵向切片。完成时需要新增 `step-01-content-pipeline.md`，并记录真实代码入口、状态机、数据库迁移、事件契约、测试和可复现实验。
