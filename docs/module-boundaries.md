# 模块边界

## 依赖方向

```text
apps/*                 -> core, agent, shared, runtime-autoconfigure
agent                  -> core, runtime-core（发布制品，按版本引入）
runtime-autoconfigure  -> runtime-core
runtime-core           -> 仅第三方库（可独立发布，不含业务代码）
core                   -> 仅第三方库
shared                 -> 仅第三方库
core 内部               content 独立；search / recommendation -> feature.api
```

禁止的边：

- `core -> agent`、`core -> shared`、`agent -> shared`
- `shared ->` 任何业务包
- `apps/a -> apps/b`
- 跨域引用 `domain` 或 `adapter`（只能引用 `*.api`）
- `runtime-core` 引用任何 `io.seekflux.core` / `io.seekflux.agent` 类型

`core` 与 `agent` 的规则由 `CoreArchitectureTest`（4 条）与 `AgentArchitectureTest`（3 条）在 `mvn test` 中强制；`runtime-core` 的"零业务依赖"由它自身的 pom 保证（没有 seekflux 业务坐标）。

## 模块清单

| 模块 | 坐标 | 职责 |
| --- | --- | --- |
| `core` | `io.seekflux:core` | 四个业务域 + 共享出站适配器 |
| `agent` | `io.seekflux:agent` | 检索编排：目标、约束、路由、追问、回退 |
| `platform/agent-runtime` | `io.github.xhfabn.seekflux:seekflux-agent-runtime-core` | 可发布 Agent 内核（独立版本） |
| `platform/agent-runtime-spring-boot-autoconfigure` | `...:seekflux-agent-runtime-spring-boot-autoconfigure` | 内核的 Spring Boot 装配 |
| `shared` | `io.seekflux:shared` | Outbox Relay、Flyway、JDBC 驱动 |
| `apps/online-server` | 可部署进程 | 搜索、Feed、交互、实时特征 |
| `apps/agent-server` | 可部署进程 | Agent 搜索 API 与运行时装配 |
| `apps/content-server` | 可部署进程 | 内容登记与媒体上传 |
| `apps/worker-runner` | 可部署进程 | Kafka 消费、索引、行为事实、特征投影 |
| `pipelines/realtime-features` | Flink Job | 事件时间窗口与短期兴趣 |

## core 内部结构

```text
io.seekflux.core
├── content/{api, domain, adapter}          内容写侧 + JdbcContentRepository
├── search/{api, domain}                    检索用例与检索端口
├── feature/{api, domain, adapter}          交互事实 + 实时特征
├── recommendation/{api, domain}            兴趣、召回、规则排序
└── adapter/{elasticsearch, http}           同时服务多个域的出站适配器
```

每个域固定三段：

- `api/`：用例接口、命令、视图、跨域 DTO —— **唯一允许被其它域引用的包**
- `domain/`：聚合、值对象、领域服务、出站端口
- `adapter/`：Jdbc、HTTP 等出站实现

`core/adapter/elasticsearch` 是唯一跨域的适配器目录：一个 ES 索引同时承载检索与 Feed 两种查询。

## agent 内部结构

```text
io.seekflux.agent
├── api/                     检索契约（use case + DTO，不依赖实现）
├── AgentSearchApplicationService
├── orchestration/           检索目标、约束、路由、追问、回退
│   └── adapter/             SearchDirectTool、DirectSearchExecutionAdapter、Redis 会话投影
└── adapter/jdbc/            会话、运行记录、恢复、工作区事实的持久化
```

Runtime 内核以发布制品引入，不在本模块内；`agent` 与 `shared` 互不依赖。

## 装配方式

四个进程都使用 `@SpringBootApplication(scanBasePackages = "io.seekflux")`。业务模块内的 `@Component`、`@Configuration` 自动装配；**进程级装配留在 apps**（例如 `AgentRuntimeConfiguration` 依赖 autoconfigure 与 actuator health，属于进程关注点）。Runtime 的装配由 `runtime-autoconfigure` 提供。

## 拆分触发条件

只有满足至少一项时才考虑拆出独立服务：需要独立扩缩容、需要不同资源类型、发布节奏冲突、故障隔离不足，或团队所有权已经独立。拆分前先确保 Port 和契约稳定。
