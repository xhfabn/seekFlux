# SeekFlux macOS 本地运行脚本

根目录的 `seekflux.sh` 是当前已实现服务的统一入口。它会把可下载的中间件安装在项目
`.runtime/`，把数据、日志和 PID 保存在 `.local/`；PostgreSQL 二进制通过 Homebrew
安装，数据目录仍属于本项目。

```bash
./seekflux.sh doctor   # 首次先检查环境
./seekflux.sh install  # 只安装中间件
./seekflux.sh up       # 安装并启动中间件、四个 Java 应用与 Web
./seekflux.sh status
./seekflux.sh logs online
./seekflux.sh logs agent
./seekflux.sh open
./seekflux.sh down
```

`up` 会依次启动 PostgreSQL、Redis、Kafka、Elasticsearch、MinIO、Content Server、
Worker Runner、Online Server、Agent Server 和 `apps/web`。Agent Server 默认使用 `8083`，避免与可选 Flink UI 的 `8082` 冲突。`open` 只打开 `http://localhost:3001/` 这一个前端入口。脚本是幂等的，已安装或已运行的组件不会重复处理。

Agent Server 默认使用无需密钥的确定性 Provider。联调 Chat Completions 兼容端点时，在 `.env` 设置 `AGENT_LLM_PROVIDER=openai-compatible`、`AGENT_LLM_ENDPOINT`、`AGENT_LLM_API_KEY`、`AGENT_LLM_MODEL` 和可选的 `AGENT_LLM_TIMEOUT_MS`，再执行 `./seekflux.sh apps-down && ./seekflux.sh apps-up`。密钥不能提交到仓库。

国外源较慢且本机已有代理时，可在根目录 `.env` 中设置：

```bash
SEEKFLUX_DOWNLOAD_PROXY=http://127.0.0.1:7890
```

如果公司内网或个人镜像提供 Elasticsearch 的 macOS 归档，可在根目录 `.env` 中
覆盖完整下载地址。未配置时使用 Elastic 官方源并进行分段并行下载：

```bash
ELASTICSEARCH_DOWNLOAD_URL=https://mirror.example/elasticsearch-8.19.0-darwin-aarch64.tar.gz
```

## 升级或新增中间件

版本集中维护在 `deploy/local/versions.env`。升级现有组件时修改版本号并再次执行
`./seekflux.sh install`；版本化二进制会并排安装，业务数据不会被删除。

新增中间件时遵循四个入口：

1. 在 `versions.env` 增加版本；
2. 在 `stack.sh` 增加 `ensure_<name>` 与 `start_<name>`；
3. 将它加入 `install_all`、`infra_up`、`infra_down` 和 `status_all`；
4. 把数据、日志、PID 分别放入 `.local/<name>/data|logs|run`。

这样更新脚本不会覆盖已有数据，也能继续使用相同的 `up/status/down` 命令。
