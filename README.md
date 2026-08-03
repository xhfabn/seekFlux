# SeekFlux

SeekFlux 是面向短视频内容平台的多模态搜索与推荐中台。当前已完成 **Phase 0 工程基线**和 **Step 1 内容登记与画像发布**：内容可以通过 HTTP 登记，经 PostgreSQL/Outbox/Kafka/Worker 异步生成基础画像并发布。搜索、推荐等后续切片仍待实现。

架构依据见 [SeekFlux.md](SeekFlux.md)，模块依赖规则见 [docs/module-boundaries.md](docs/module-boundaries.md)。如果希望按实现顺序学习项目，请从 [docs/learning/README.md](docs/learning/README.md) 开始；其中包含工程架构图、模块职责、开发路线和当前阶段日志。

## 学习型开发文档

本项目采用“功能实现与学习文档同步交付”的方式推进。每完成一个可运行的纵向切片，都要在 `docs/learning/` 新增或更新对应文档，说明该部分解决的问题、架构位置、核心流程、关键代码、设计取舍、验证命令和练习，并更新学习日志状态。影响长期架构的决定同时写入 `docs/adr/`；影响 API、事件或特征的变更同时更新 `contracts/`。没有通过测试或验收的计划项不得在学习日志中标记为完成。

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

- 尚未实现 Elasticsearch Mapping；内容进入检索索引属于 Step 2；
- 当前画像 Worker 只根据提交元数据生成可重复的基础画像，尚未接入 ASR、OCR、视觉理解或模型服务；
- 尚未实现搜索、Feed、互动、实时特征和模型排序业务；
- 没有把九个 Context 拆成九个进程。

下一阶段优先实现关键词搜索基线，再依次推进 Feed、曝光/行为闭环、实时特征和模型排序。Step 1 的实现与学习说明见 [内容登记与画像发布](docs/learning/step-01-content-pipeline.md)。
