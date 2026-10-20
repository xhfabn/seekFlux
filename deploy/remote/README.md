# 本地代码连接服务器中间件

本地运行 Content、Online、Agent、Worker 和 Web；PostgreSQL、Redis、Kafka、Elasticsearch、
MinIO 全部运行在服务器。业务进程通过服务器 IP/域名和端口直连，不创建 SSH 隧道，也不安装
本地中间件或要求本地 Docker。

## 换一台笔记本

安装 JDK 21、Maven 3.9+、Node.js 22.13+、npm 和 Python 3，下载 `dev` 分支后执行：

```bash
./seekflux.sh remote-init
```

在生成的 `.env.remote` 中填写 `POSTGRES_PASSWORD`、`MINIO_ROOT_PASSWORD`，以及有变化的
主机和端口。值包含空格、`#`、`$` 或单引号时使用 shell 引号；文件只支持赋值，不执行命令
替换或变量展开。配置默认使用 `36.140.162.30`，修改一个 `SEEKFLUX_REMOTE_HOST` 会同时
更新五个中间件的连接地址。端口可以分别通过 `SEEKFLUX_REMOTE_*_PORT` 覆盖。

如果笔记本已有服务器 SSH 密钥权限，也可以一次性读取当前容器的账号、密码和映射端口：

```bash
./seekflux.sh remote-init --from-server
```

这个可选初始化命令以及下文的服务器运维命令使用 SSH。业务运行命令不需要 SSH。
私有配置权限为 `600`，已经被 Git 忽略；不要把它发到公开仓库。

```bash
./seekflux.sh remote-check  # 检查五个中间件的真实协议/健康接口
./seekflux.sh remote-up     # 构建并启动本地 Java 服务和 Web
./seekflux.sh remote-down   # 仅停止本地业务进程，保留服务器中间件
```

打开 `http://localhost:3001/`。Web Bridge 仍连接本地的三个 Java API，后端再连接服务器。
切换本地/远程模式前，先用 `./seekflux.sh apps-down` 停止旧的本地业务进程。

远程模式将 `.env.example` 的默认业务参数与 `.env.remote` 合并，生成权限为 `600` 的
`.local/remote/runtime.env`；不会读取或覆盖原 `.env`。可在 `.env.remote` 中配置模型 Provider、
Web/API 端口等业务参数。macOS launchd 与 Linux nohup 均显式传递这个文件，避免重新加载
本地中间件地址。Bridge 地址自动跟随本地 API 端口，也可通过 `SEEKFLUX_CONTENT_API_BASE`、
`SEEKFLUX_ONLINE_API_BASE`、`SEEKFLUX_AGENT_API_BASE` 单独覆盖。
多模态模型默认关闭；它是独立可选服务，不由这组中间件连接配置自动部署。

## 服务器启动与 Kafka 地址

服务器的项目 `.env` 中配置真实账号、密码和端口，并增加：

```dotenv
KAFKA_ADVERTISED_HOST=36.140.162.30
```

在服务器执行：

```bash
docker compose --env-file .env -f deploy/compose/compose.yml up -d --wait postgres redis kafka elasticsearch minio
docker compose --env-file .env -f deploy/compose/compose.yml up -d kafka-init minio-init
```

也可以从有 SSH 权限的笔记本执行 `./seekflux.sh remote-infra-up`，它只选择上述中间件和
初始化服务，不启动远端业务应用，不执行删除容器/卷的命令。

Kafka 内部继续公布 `kafka:29092`，外部公布 `${KAFKA_ADVERTISED_HOST}:${KAFKA_PORT}`。
只修改笔记本的 bootstrap 地址还不够；broker 不能向远端客户端返回 `localhost` 或 `kafka`。
`remote-check` 会读取 broker 元数据并验证公布的地址，避免出现 TCP 能连接但消费失败的情况。

MinIO 的 `S3_ENDPOINT` 自动指向服务器。`MINIO_PUBLIC_BASE` 默认也使用服务器地址，必须
能被浏览器和服务器 Worker 同时访问；不要把持久化的媒体 URL 写成笔记本的回环地址。

## 当前边界

- 服务器与云防火墙需要允许笔记本访问对应的五个端口；脚本不会修改防火墙规则。
- `remote-check` 检查 PostgreSQL 协议握手、Redis PING、Kafka 元数据与 broker 可达性、
  Elasticsearch 集群健康、MinIO 存活；它不写业务数据，也不替代数据库密码和文件上传验收。
- 默认连接服务器现有数据。多个笔记本以及服务器 Worker 共用数据库、缓存、索引、Topic、
  消费者组和业务消费进度，不提供环境隔离；本地 Worker 可能与服务器 Worker 分担事件。
  需要完全独立调试时，应另建独立的服务器中间件实例及数据卷，再改本配置的地址和端口。
- HTTP/明文协议沿用当前开发部署。公网使用时按实际需求限制访问来源并配置鉴权/TLS。

2026-10-20 已从笔记本通过服务器 IP 验证五个中间件的协议/健康接口，以及 PostgreSQL
真实账号密码只读查询和 MinIO 签名鉴权请求；Kafka 已返回可达的服务器外部地址。
自动化启动器测试覆盖配置传递，不将这些检查等同于完整业务或跨版本迁移验收。
