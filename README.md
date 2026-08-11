# SeekFlux

SeekFlux 是一个面向短视频内容平台的搜索、推荐与 Search Agent 项目。它以可解释的 Direct Search 作为稳定底座，在复杂搜索、多轮约束和工具调用确有价值时才进入自研 Agent Runtime；C 端提供“发现”内容消费界面，B 端提供用户画像与内容工作台。

项目的产品目标、长期架构和模块关系见 [SeekFlux.md](SeekFlux.md) 与 [模块边界](docs/module-boundaries.md)。开发进度、下一步和验收门槛只在 [学习路线](docs/learning/README.md) 维护。

## 产品能力

- 内容登记经 PostgreSQL、事务 Outbox、Kafka、Worker 和 Elasticsearch 形成可搜索的内容画像；
- C 端发现页支持画像驱动的内容发现、关键词搜索、热门/兴趣/相似内容召回、规则排序，以及由 Agent Server 驱动的多轮 AI 搜索；
- C 端曝光、点击、播放、点赞、收藏、完播和负反馈通过 Interaction API 可靠入站，以真实 request/trace/content/position/surface 完整归因，并经事务 Outbox、Kafka 和幂等 Worker 形成行为事实；
- 行为事件经 `realtime-window-v1` 生成 30 分钟短期兴趣与 5 分钟内容热度快照，写入 Redis 后由 Search/Feed 消费；过期或不可用时保留现有规则结果并明确降级；
- B 端用户画像与内容工作台通过真实后端接口管理兴趣约束和内容标签；
- Direct Search 使用 BM25/kNN 双路召回、RRF 融合、结构化过滤、共同 Deadline、单路降级和 Search Trace；
- Search Agent 支持简单/复杂 Query 路由、SearchPlan、多轮 ConstraintPatch、动态工具集、并行宽搜/精搜、候选复用、追问与 Direct Fallback；
- Agent Runtime 提供 `Router → FeaturePipeline → SessionExecutor → AgentLoop` 主链路、Session 事件、运行轨迹和版本冻结；
- 多实例执行使用 Redis owner-CAS 与 fencing token，支持失主接管、跨实例取消、优雅停机和旧 owner 提交隔离；
- Agent 终态通过事务 Outbox 发送至 Kafka，由幂等审计消费者记录；模型与 Tool 分别受 Bulkhead 保护；
- OpenAI-compatible Provider 支持结构化决策、usage/成本计量和 Shadow 策略旁路。默认 Provider 为可复现的确定性实现。

## 使用界面

- C 端发现应用：`http://localhost:3001/`
- 用户画像：在 Web 中维护用户兴趣与搜索约束。
- 内容工作台：在 Web 中登记内容，并通过内容标签影响搜索与推荐匹配。

`apps/web` 是唯一前端工程。页面通过同源 Bridge 调用 Content、Online 与 Agent API；前端不维护模拟的画像匹配或内容推荐逻辑。

## 架构概览

```text
Web
 ├─ C 端发现 / 关键词搜索 / 多轮 AI 搜索
 └─ B 端用户画像 / 内容工作台

Content Server → PostgreSQL + Outbox → Kafka → Worker → Elasticsearch
Online Server  → Direct Search / Feed / Interaction API
Agent Server   → AgentOrchestration → Agent Runtime → Search Tools → Direct Search
Interaction Topics → Flink（生产）/ Worker 参考投影（本地）→ Redis → Search / Feed
```

工程保持模块化单体与独立 Agent Server 的组合：业务语义在 `contexts/`，通用基础能力在 `platform/`，应用装配在 `apps/`。HTTP 接口采用 Spring MVC 同步 JSON；JDBC/HikariCP、Redis 与 Elasticsearch 使用同步 Adapter。Agent 内部的模型调用和 Tool fan-out 只在命名、有界、可观测的执行器中并发，不向领域 Port 或 HTTP 扩散 `Mono`/`Flux`。

Agent Runtime 的详细设计见 [docs/agent-runtime.md](docs/agent-runtime.md)，多实例可靠性与 Ark-Leto 参考实现的对照见 [ADR-006](docs/adr/ADR-006-agent-reliability-fencing-outbox-shadow.md)。

## 本地运行

环境要求：JDK 21、Maven 3.9+、Docker Engine 26+（Compose 方式）、建议至少 8 GB 可用内存。具体环境基线见 [docs/environment.md](docs/environment.md)。

macOS 推荐使用统一脚本：

```bash
./seekflux.sh doctor
./seekflux.sh up
./seekflux.sh status
./seekflux.sh open
./seekflux.sh down
```

脚本管理 PostgreSQL、Redis、Kafka、Elasticsearch、MinIO、Content Server、Worker Runner、Online Server、Agent Server 和 Web。macOS 下 Java/Web、Kafka、Elasticsearch、MinIO 使用 launchd 托管；数据和日志保存在 `.local/`，版本化下载文件保存在 `.runtime/`。详细命令见 [macOS 本地运行脚本](deploy/local/README.md)。

也可使用 Docker Compose：

```bash
cp .env.example .env
docker compose --env-file .env -f deploy/compose/compose.yml up -d
```

默认端口：PostgreSQL `5432`、Redis `6379`、Kafka `9092`、Elasticsearch `9200`、MinIO `9000/9001`、Online `8080`、Content `8081`、Agent `8083`、Web `3001`。

## 验证与文档

```bash
mvn -pl platform/agent-runtime,apps/agent-server,apps/worker-runner -am test
python3 evals/run_agent_search_eval.py
python3 evals/run_complex_agent_eval.py
python3 evals/run_agent_reliability_eval.py
python3 evals/run_interaction_loop_eval.py
python3 evals/run_realtime_feature_eval.py
```

- [学习路线与阶段验收](docs/learning/README.md)
- [系统架构设计](SeekFlux.md)
- [Agent Runtime 内核](docs/agent-runtime.md)
- [API 契约](contracts/openapi/seekflux-v1.yaml)
- [评测资产](evals/README.md)
- [架构决策记录](docs/adr/)

真实付费模型的质量、Token 和成本基线必须在配置对应 Provider 后独立生成；仓库默认使用确定性 Provider，保证本地回归可复现且不伪造外部模型数据。
