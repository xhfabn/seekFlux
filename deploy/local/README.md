# SeekFlux macOS / Ubuntu 本地运行脚本

根目录的 `seekflux.sh` 是当前已实现服务的统一入口。它会把可下载的中间件安装在项目
`.runtime/`，把数据、日志和 PID 保存在 `.local/`。macOS 的 PostgreSQL 二进制可由
Homebrew 安装；Ubuntu 使用系统安装的 PostgreSQL 17 工具，数据目录仍属于本项目。

Ubuntu 24.04/22.04 首次运行前安装基础依赖（PostgreSQL 17 可使用 PostgreSQL 官方
APT 仓库；若系统安装在 `/usr/lib/postgresql/17/bin`，脚本会自动识别）：

```bash
sudo apt-get install curl build-essential netcat-openbsd xdg-utils openjdk-21-jdk maven nodejs npm python3-venv ffmpeg
sudo apt-get install postgresql-17 postgresql-client-17
```

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
Worker Runner、Online Server、Agent Server 和 `apps/web`。Agent Server 默认使用 `8083`，避免与可选 Flink UI 的 `8082` 冲突。`open` 打开 `.env` 中 `WEB_SERVER_PORT` 对应的前端入口。脚本是幂等的，已安装或已运行的组件不会重复处理。
Java 应用在启动前执行 `mvn clean package`，避免切换分支或恢复工作区后复用内容已经过期但
时间戳相同的 `target/classes` 资源。

Web 默认只监听 `localhost:3001`。受控开发环境需要通过云平台端口映射远程访问时，可在根目录
`.env` 中配置对外监听端口；例如平台映射端口为 `10000`：

```bash
WEB_SERVER_HOST=0.0.0.0
WEB_SERVER_PORT=10000
```

这只对外监听 Web；PostgreSQL、Redis、Kafka、Elasticsearch、MinIO 和模型服务仍保持回环地址。
Vinext 是开发服务器，不应把该配置当作生产部署方式。

Step 9 的本地链路由 Worker Runner 内置 JDBC 参考投影器消费 Interaction Topic，生成与生产 Flink Job 相同版本的短期兴趣/内容热度快照并写入 Redis，因此 `./seekflux.sh up` 不要求额外启动 Flink 集群。生产 Flink Job 的可提交 Jar 位于 `pipelines/realtime-features/target/realtime-features-0.1.0-SNAPSHOT.jar`；同一环境只能选择一个权威投影路径，不能让参考投影器和 Flink 使用同一消费进度重复承担生产职责。

macOS 下 Java/Web 以及 Kafka、Elasticsearch、MinIO 通过 launchd 托管；Ubuntu 下使用
`nohup` 和项目内 PID 文件管理。PostgreSQL 和 Redis 使用自身的 daemon 管理。`down`
会停止由当前项目启动的进程，不会停止 Ubuntu 系统中另外运行的 PostgreSQL 实例。
在 Linux root 容器中，项目 PostgreSQL 由系统 `postgres` 用户运行；Elasticsearch 使用脚本
自动创建的 `seekflux` 系统用户，避免以 root 运行。Web 本地开发使用 Vinext 的 Node Runtime，
因此 Ubuntu 20.04 不依赖新版 `workerd` 所要求的 glibc 2.35；默认 Cloudflare 构建与测试配置
不受影响。

启用 Step 11 多模态模式时，在根目录 `.env` 设置：

```bash
MULTIMODAL_ENABLED=true
MULTIMODAL_DEVICE=cuda
HF_HOME=/path/on/a-large-disk/seekflux-huggingface
```

`MULTIMODAL_DEVICE` 在 NVIDIA 主机上设为 `cuda`；无 GPU 时可设为 `cpu`，Apple Silicon 可设为
`mps`。首次启动会下载 SigLIP 与（第一次处理视频音轨时）faster-whisper 权重。代理环境中如需
稳定断点续传，可额外设置 `HF_HUB_DISABLE_XET=1`。本地 MinIO 回环地址会由 Sidecar 与验收脚本
强制直连，不会错误经过 HTTP 代理。

Agent Server 默认使用无需密钥的确定性 Provider。联调 Chat Completions 兼容端点时，在 `.env` 设置 `AGENT_LLM_PROVIDER=openai-compatible`、`AGENT_LLM_ENDPOINT`、`AGENT_LLM_API_KEY`、`AGENT_LLM_MODEL`、可选的 `AGENT_LLM_TIMEOUT_MS` 以及输入/输出百万 Token 单价，再执行 `./seekflux.sh apps-down && ./seekflux.sh apps-up`。密钥不能提交到仓库。

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
