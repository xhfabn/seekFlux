# SeekFlux

SeekFlux 是面向短视频内容平台的多模态搜索与推荐中台。本目录当前完成的是 **Phase 0 工程基线**：环境、模块边界、契约位置和本地中间件已经建立，尚未实现业务代码。

架构依据见 [SeekFlux.md](SeekFlux.md)，模块依赖规则见 [docs/module-boundaries.md](docs/module-boundaries.md)。

## 当前工程边界

- `apps/online-server`：Search、Feed、Interaction API 的进程装配；
- `apps/content-server`：内容控制面和处理任务入口；
- `apps/worker-runner`：内容理解、索引、特征写入 Worker 的进程装配；
- `apps/training-runner`：Python 样本、训练、评测和注册环境；
- `contexts/`：九个 DDD 限界上下文，均预留六边形分层；
- `platform/`：检索、持久化、消息、模型服务、可观测 Adapter；
- `pipelines/realtime-features`：Flink 实时行为和特征计算；
- `contracts/`：API、事件和特征契约；
- `deploy/`：本地 Compose、后续 Helm、Dashboard 和告警；
- `evals/`：评测数据与版本化结果。

首期保持模块化单体，`online-server` 装配 Search/Recommendation 等 Context。只有出现独立扩缩容、发布或故障隔离需求后，才将模块拆成服务。

## 环境要求

- JDK 21
- Maven 3.9+
- Python 3.12（仅训练侧）
- Docker Engine 26+ 与 Docker Compose v2
- 建议本机至少 8 GB 可用内存

具体工具链基线与资源建议见 [docs/environment.md](docs/environment.md)。

## 本地基础设施

不使用 Docker 时，可用统一脚本下载、安装并启动 PostgreSQL、Redis、Kafka、Elasticsearch 和 MinIO：

```bash
./deploy/local/infra.sh start
./deploy/local/infra.sh status
./deploy/local/infra.sh stop
```

首次 `start` 会把四个二进制组件下载到 `.runtime/`（支持断点续传），数据和日志保存在 `.local/`。MinIO API 使用 `9000`，控制台使用 `9002`，以避开当前开发环境占用的 `9001`。

只下载和安装、不启动服务：

```bash
./deploy/local/infra.sh download
```

Docker Compose 方式仍然保留如下。

复制环境变量并按需修改：

```bash
cp .env.example .env
```

启动核心组件：

```bash
docker compose --env-file .env -f deploy/compose/compose.yml up -d
```

同时启动可观测组件：

```bash
docker compose --env-file .env -f deploy/compose/compose.yml --profile observability up -d
```

需要开发实时特征任务时，再启用 Flink：

```bash
docker compose --env-file .env -f deploy/compose/compose.yml --profile streaming up -d
```

验证配置和 Java 模块：

```bash
docker compose --env-file .env.example -f deploy/compose/compose.yml config --quiet
mvn validate
```

默认端口：PostgreSQL `5432`、Redis `6379`、Kafka `9092`、Elasticsearch `9200`、MinIO `9000/9001`、Flink UI `8082`、OTLP `4317/4318`、Prometheus `9090`、Grafana `3000`。

## 当前刻意未做

- 没有 Controller、Use Case、领域实体和 Adapter 实现；
- 没有数据库业务表、Elasticsearch Mapping 和模型文件；
- 没有启动类，因此应用模块目前用于锁定依赖与边界，不能启动业务服务；
- 没有把九个 Context 拆成九个进程。

下一阶段应优先实现内容登记与画像发布、搜索基线、Feed 基线、曝光/行为闭环，再引入实时特征和模型排序。
