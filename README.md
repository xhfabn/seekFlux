# SeekFlux

SeekFlux 是面向短视频内容平台的多模态搜索、推荐与 Search Agent 系统。当前代码已经跑通内容登记、PostgreSQL/Outbox/Kafka/Worker 画像生产、Elasticsearch BM25/kNN 混合检索、热门／兴趣／相似内容多路召回，以及简单/复杂 Query 路由、多轮 SearchGoal、并行 Search Tool、确定性回退和多实例 Agent 可靠性链路。

> 当前开发 Step、Agent Phase、下一步及完成门槛只在[学习路线](docs/learning/README.md)维护，避免多个 README 重复记录后产生冲突。架构目标不等于已经实现，当前只把测试与评测证明的 Phase 0～3 标记为完成。

架构依据见 [SeekFlux.md](SeekFlux.md)，模块依赖规则见 [docs/module-boundaries.md](docs/module-boundaries.md)。如果希望按实现顺序学习项目，请从 [docs/learning/README.md](docs/learning/README.md) 开始；其中包含唯一的开发路线、当前状态、按序阶段日志和验收门槛。

## 学习型开发文档

本项目采用“功能实现与学习文档同步交付”的方式推进。每轮产生实际交付结果后，都要按根目录 [`AGENTS.md`](AGENTS.md) 检查并更新学习文档：`docs/learning/README.md` 是唯一进度与路线入口，每个 `step-NN-*.md` 按实现顺序记录一个纵向切片；只有真正开始新 Step 时才创建新文件。影响长期架构的决定同时写入 `docs/adr/`，影响 API、事件或特征的变更同时更新 `contracts/`。不得重复创建架构总览、阶段总结或同一 Step 的多份文档，也不得把未通过测试或验收的计划标记为完成。

## 当前工程边界

- `apps/online-server`：Search、Feed、Interaction API 的进程装配；
- `apps/content-server`：内容控制面和处理任务入口；
- `apps/agent-server`：独立 Agent API、Runtime、AgentOrchestration 和 Tool 装配；
- `apps/web`：唯一前端工程，承载 C 端发现应用与 B 端画像／内容工作台；
- `apps/worker-runner`：内容理解、索引、特征写入和 Agent 终态审计 Worker 的进程装配；
- `apps/training-runner`：Python 样本、训练、评测和注册环境；
- `contexts/`：当前十个业务限界上下文，其中 `AgentOrchestration` 管理 SearchGoal、约束、追问和回退语义；
- `platform/`：Agent Runtime、检索、持久化、消息、模型服务、可观测 Adapter；
- `pipelines/realtime-features`：Flink 实时行为和特征计算；
- `contracts/`：API、事件和特征契约；
- `deploy/`：本地 Compose、后续 Helm、Dashboard 和告警；
- `evals/`：评测数据与版本化结果。

Agent Phase 3 已经实现：`apps/agent-server` 负责独立进程入口和 Provider/Tool Adapter，`contexts/agent-orchestration-context` 负责 Query Mode、SearchPlan、版本化 SearchGoal/ConstraintPatch 与回退语义，`platform/agent-runtime` 负责 Router、FeaturePipeline、SessionExecutor、有限步 AgentLoop、动态并行 Tool、fencing、跨实例取消、Bulkhead、Shadow 和运行事件。Search Tool 只能调用 Search Use Case，不能直接访问 Elasticsearch、Redis 或数据库。详细内核见 [`docs/agent-runtime.md`](docs/agent-runtime.md)。

首期保持模块化单体，`online-server` 装配 Search/Recommendation 等 Context。只有出现独立扩缩容、发布或故障隔离需求后，才将模块拆成服务。

在线与内容接口统一采用 Spring MVC 普通返回值，PostgreSQL 使用 JDBC/HikariCP，Redis 与 Elasticsearch Adapter 使用同步客户端。Kafka Worker 保持事件驱动；推荐的多路召回仅在独立有界线程池内并行，不向 Controller 或领域 Port 暴露 `Mono`/`Flux`。

Agent 的模型调用和 Tool 执行只允许在 Agent 边界内使用明确、有界、可观测的并发；普通 Search/Feed/Agent API 继续返回同步 JSON，Direct Search 不依赖 Agent 并始终作为确定性回退。

## 当前可操作页面

- SeekFlux Web：`http://localhost:3001/`

`apps/web` 是项目唯一前端入口：默认的“发现”是面向普通用户的纵向内容消费应用；“用户画像”和“内容工作台”是面向运营／创作者的 B 端控制台。页面通过同源 Bridge 调用 Content API 与 Online API；媒体上传和实时行为回流尚未完成的部分会显示明确占位。完整启动和实验见 [Step 2 学习文档](docs/learning/step-02-keyword-search.md)和 [Step 3 学习文档](docs/learning/step-03-feed-baseline.md)。

## 环境要求

- JDK 21
- Maven 3.9+
- Python 3.12（仅训练侧）
- Docker Engine 26+ 与 Docker Compose v2
- 建议本机至少 8 GB 可用内存

具体工具链基线与资源建议见 [docs/environment.md](docs/environment.md)。

## 本地基础设施

macOS 上推荐使用根目录统一脚本。它会安装并启动 PostgreSQL、Redis、Kafka、Elasticsearch、MinIO，以及 Content Server、Worker Runner、Online Server、Agent Server 和 Web：

```bash
./seekflux.sh doctor
./seekflux.sh up
./seekflux.sh status
./seekflux.sh open
./seekflux.sh down
```

首次 `up` 会通过 Homebrew 安装 PostgreSQL，并把其余版本化二进制下载到 `.runtime/`；所有项目数据和日志保存在 `.local/`。macOS 下 Java/Web 与 Kafka、Elasticsearch、MinIO 由 launchd 托管，不会随启动命令退出。统一页面位于 `http://localhost:3001/`。MinIO API 使用 `9000`，控制台使用 `9002`。中间件版本统一维护在 `deploy/local/versions.env`，以后升级后重新运行 `install` 或 `up` 即可。详细命令见 [macOS 本地运行脚本](deploy/local/README.md)。

只安装、不启动服务：

```bash
./seekflux.sh install
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

默认端口：PostgreSQL `5432`、Redis `6379`、Kafka `9092`、Elasticsearch `9200`、MinIO `9000/9001`、Online `8080`、Content `8081`、Flink UI `8082`、Agent `8083`、Web `3001`、OTLP `4317/4318`、Prometheus `9090`、Grafana `3000`。

## 当前刻意未做

- 当前画像 Worker 只根据提交元数据生成可重复的基础画像，尚未接入 ASR、OCR、视觉理解或模型服务；
- 当前语义通道是可复现的 Hashing n-gram ANN 基线，尚未实现专业中文分词、预训练文本/视频 Embedding 和 Cursor 深分页；
- Feed 当前使用新鲜度作为无行为数据时的热门代理，兴趣来自显式输入；真实热度、最近行为兴趣和 Item-Item 协同后移到可选 Step 8～10；
- 尚未实现互动、实时特征和模型排序业务；
- Agent 默认使用确定性决策 Provider，已经提供 OpenAI-compatible Adapter、usage/价格计量与 Shadow，但尚无真实付费模型质量/Token/成本基线；HITL、Handoff、子 Agent、MCP、Checkpoint/写 Tool 副作用账本、上下文压缩和实时流式 Push 仍未实现；
- 没有把十个 Context 拆成十个进程。

后续工作的唯一顺序、状态和验收门槛见[学习路线](docs/learning/README.md)，根 README 不再维护路线副本。
