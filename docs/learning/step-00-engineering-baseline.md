# Step 0：工程基线

> 本文按序记录最初建立仓库骨架时的历史切片。它对应开发 Step 0，不是 [`SeekFlux.md`](../../SeekFlux.md) 中的 Agent Phase 0。当前项目已经完成到 Step 6，真实下一步见[学习路线首页](README.md)。

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

这里列出的是 Step 0 完成时的状态，不应用来判断今天的功能进度。当时尚未创建第十个 `AgentOrchestration` Context、`apps/agent-server` 和 `platform/agent-runtime`；它们现在已经由 Step 5 实现。目录中的 `.gitkeep` 或空模块仍只代表边界预留，不能单独作为完成证据。

## 架构上学什么

### 1. 聚合模块与可执行模块不同

根工程、`contexts`、`platform`、`apps` 和 `pipelines` 的 POM 使用 `packaging=pom`，作用是组织构建；`online-server` 等子模块才会逐步成为可执行或可依赖的 JAR。

### 2. 逻辑边界与部署边界不同

Step 0 建立的九个 Context 是逻辑业务边界，但首期不会部署成九个微服务。Step 5 新增 `AgentOrchestration` 作为第十个业务 Context；Agent Runtime 属于 Platform，不应被误列为第十一个 Context。多个 Context 可以被同一应用装配，同时仍禁止共享可变实体和越界访问数据库。

### 3. 六边形架构控制依赖方向

领域层描述业务规则；Application 调用输入/输出 Port；Adapter 负责 HTTP、数据库、Kafka、Elasticsearch 等细节。这样单元测试领域规则时不需要启动中间件。

### 4. 契约先确定协作形状

`contracts/openapi` 定义同步 API 的初始形状，`contracts/events` 定义异步 Envelope，`contracts/features` 预留特征定义。契约不是一次定死，但后续必须显式版本化并兼容演进。

### 5. 为什么 Java 与 Python 共存

低延迟在线服务、Worker、Agent Runtime 和 Flink Job 使用 Java；训练及部分算法实验可以使用 Python。Direct Search 与 Agent Eval 的格式属于版本化评测契约，不因使用哪种语言而改变。Python 是后置训练/实验工具，不是开始 Agent 的前置条件。

## 关键文件入口

| 文件 | 阅读目的 |
| --- | --- |
| [`pom.xml`](../../pom.xml) | 理解 Maven Reactor 和统一版本管理 |
| [`docs/module-boundaries.md`](../module-boundaries.md) | 理解允许和禁止的依赖方向 |
| [`apps/online-server/pom.xml`](../../apps/online-server/pom.xml) | 理解应用如何装配 Context 与 Platform |
| `apps/training-runner/pyproject.toml`（历史入口，现已移除） | 当时用于理解 Python 训练环境边界 |
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

## 2026-10-20：Online HTTP 包结构维护

本轮属于工程结构维护，不新增 Step，也不改变学习路线的阶段状态。

- `online-server/api` 按搜索、推荐、交互、实时特征、兴趣分包，Controller 与专用 DTO 同包，测试同步迁移。
- 服务内公共错误模型与异常处理集中到 `api/error`；`SearchExceptionHandler` 更名为
  `OnlineExceptionHandler`，作用范围限定 online API，未抽取跨服务 Web 模块。
- 错误码根据实际 HandlerMethod 的业务包选择；兴趣、实时特征不再误报推荐错误。
  未识别的业务包使用 `INVALID_REQUEST`，声明范围以外的异常继续交给 Spring。
- `ApiError` 保持 `code / message / timestamp`，显式固定时间戳为 ISO 字符串。
  原有 409、503 映射和多模态配置开关保持现有语义。
- 既有配置类移至 `configuration` 的调整一并保留，Spring 上下文启动测试通过。

入口与长期边界见 [模块边界](../module-boundaries.md) 和
[ADR-019](../adr/ADR-019-online-rest-package-organization.md)；错误码事实见
[OpenAPI](../../contracts/openapi/seekflux-v1.yaml)。核心代码入口为
[OnlineExceptionHandler](../../apps/online-server/src/main/java/io/seekflux/apps/onlineserver/api/error/OnlineExceptionHandler.java)
及 [HTTP 错误回归测试](../../apps/online-server/src/test/java/io/seekflux/apps/onlineserver/api/error/OnlineExceptionHandlerTest.java)。

验证命令：`mvn -pl apps/online-server -am test`。2026-10-20 实测 core 44 项、online-server
21 项全部通过，无失败或跳过；其中 9 个错误响应场景覆盖各业务、多模态、错误 JSON 和应用路径前缀，
并校验时间戳可解析。此验证包含 Spring 上下文与 MockMvc，不替代真实中间件或检索质量验收。

## 2026-10-20：本地代码直连服务器中间件

本轮维护工程启动配置，不改变业务契约或 Step 11 路线。

- 新增 [`.env.remote.example`](../../.env.remote.example) 和
  [`deploy/remote/remote.py`](../../deploy/remote/remote.py)：单一主机配置派生 PostgreSQL JDBC、
  Redis、Kafka、Elasticsearch、MinIO 的远程连接；本地仅启动业务代码，不安装中间件或建立隧道。
- `remote-init` 创建 Git 忽略、权限 `600` 的私有配置；可选 `--from-server` 通过一次性 SSH
  读取已运行容器的凭据和端口。凭据不输出到终端。`remote-infra-up` 通过服务器 Compose
  选择性启动五个中间件及两个初始化服务；业务连接不依赖 SSH。
- `remote-up/down` 复用本地业务启动器；macOS launchd 和 Linux nohup 显式传递
  `.local/remote/runtime.env`，避免被原 `.env` 的本地地址覆盖。Agent Server 使用已有的
  手工 Runtime 装配，远程模板关闭公共库的重复自动装配。
- Kafka 的外部公布地址增加 `KAFKA_ADVERTISED_HOST`，默认保留本地行为；容器内部地址
  仍为 `kafka:29092`。MinIO 上传连接和持久化的公开媒体 URL 使用服务器地址。
- 检查失败时 `remote-up` 在启动应用前退出；尤其会拒绝返回 `localhost`/容器名的 Kafka
  broker 元数据。详细边界和操作命令见[远程开发说明](../../deploy/remote/README.md)。

验证入口：

```bash
python3 -m unittest discover -s deploy/remote -p 'test_*.py' -v
bash -n seekflux.sh deploy/local/stack.sh deploy/local/run-java-app.sh deploy/local/run-web-app.sh
./seekflux.sh remote-check
git diff --check
```

自动化覆盖统一地址/自定义端口、私有配置优先级与权限、凭据不输出、停止范围、Kafka
公布地址检查与分片协议响应、Java/Web 子进程实际读取远程配置。远程模式默认共享服务器
现有数据和消费进度，不提供隔离；它不替代跨版本数据迁移与完整业务链路验收。

本轮完成证据：7 项自动化测试、Shell 语法与 `git diff --check` 通过；从笔记本直连
`36.140.162.30` 的五个中间件协议/健康检查全部通过，PostgreSQL 密码登录后的只读查询
和 MinIO S3 签名鉴权请求通过。服务器 Kafka 保留同一镜像和数据卷，仅更新外部地址及
内部健康检查后重建，容器健康检查通过。没有以此宣称已验收完整业务流程。

## 2026-10-20：Windows 原生启动与远程连通验收

本轮继续维护工程启动与打包，不新增 Step，也不改变路线状态。

- `agent-server` 补充对 `shared` 的直接依赖，修复运行 JAR 缺少 PostgreSQL 驱动的问题。
- 从 `main` 原样恢复 V1–V16 到 `shared/src/main/resources/db/migration`；逐文件字节比对
  以及 JAR 资源检查通过，四个服务均成功校验远程现有迁移，未执行新迁移。
- `AgentCapabilityController` 移除 `final`，修复方法校验代理导致的真实启动失败；新增
  [代理与参数校验回归](../../apps/agent-server/src/test/java/io/seekflux/apps/agentserver/interfaces/rest/AgentCapabilityValidationTest.java)，
  使用 Spring 上下文验证代理创建及超长 Session ID 的校验，而非只做 standalone MockMvc。
- 在 Windows 使用私有临时启动器直接运行四个 Java JAR 与 Vinext，远程环境通过进程环境变量
  注入；Node 使用本项目运行目录内的 22.20.0，未替换系统 Node 16。正式 Shell 启动器尚不支持
  Windows 原生，Python 使用 `-X utf8` 绕过默认 GBK。操作边界见
  [远程开发说明](../../deploy/remote/README.md#windows-本机实测补充2026-10-20)。
- 经用户授权，追加本机专用 SSH 公钥并配置本机别名 `seekflux-dev`；BatchMode 免密登录
  实测通过，已有授权保留。服务器密码未写入仓库；中间件私有配置仍由 Git 忽略。

验证命令与证据：

```powershell
mvn -pl apps/content-server,apps/online-server,apps/agent-server,apps/worker-runner -am package
mvn -pl apps/agent-server -am package '-Dtest=AgentCapabilityValidationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
python -X utf8 deploy/remote/remote.py check
ssh -o BatchMode=yes seekflux-dev 'echo SSH_KEY_AUTH_OK'
git diff --check
```

首次构建的 236 项测试及新增代理回归测试通过。五个中间件协议检查、PostgreSQL 实际登录和
迁移校验、MinIO 签名桶 HEAD 均通过；Content/Online/Agent 健康接口均为 `UP`。
Web 首页、搜索及其 Bridge 代理、内容详情均为 HTTP 200；Worker 加入远程 Kafka 消费者组，
本轮未生产新事件验证消费闭环。8080 被本机其他应用占用，Online 私有端口改为 18080，
Content/Agent/Web 保留 8081/8083/3001；日志和本机验收元数据保存在 Git 忽略的 `.local/remote/`。

剩余边界：搜索和 Bridge 实测约 7–8 秒，推荐为空；显式 Agent 请求返回 3 条结果，但为
`AGENT_TO_DIRECT_FALLBACK / AGENT_DEADLINE_EXCEEDED`，不代表 Agent 正常完成。
抽查历史媒体 URL 仍为本机回环地址，原地址访问失败，相同文件改用服务器 MinIO 地址返回 200；
本轮不改写历史媒体数据。新启动配置不能自动修复旧数据或串行远程特征读取的延迟。

## 2026-10-20：Windows 一键启动与停止

Windows 正式入口为 [seekflux.ps1](../../seekflux.ps1)，底层由
[windows_apps.py](../../deploy/remote/windows_apps.py) 管理四个后端和前端，配置仍来自
Git 忽略的 `.env.remote`。`remote.py` 显式按 UTF-8 读写配置，兼容中文和 UTF-8 BOM；
不再依赖先前的本机临时启动器。本轮不新增 Step 或改变路线状态。

- 提供 `up/down/restart/status/logs/check/init`；普通启动先构建 Java，`-NoBuild` 使用已有 JAR。
  优先选择项目内满足要求的 Node，缺少 Web 依赖时用对应 npm 安装，不替换系统运行时。
- 启动前检查端口冲突，记录 PID、创建时间及可执行文件身份；仅停止身份仍匹配的项目进程树，
  不停止其他占端口程序或服务器中间件。过期 PID 不执行终止；停止失败保留记录。
- 启停命令互斥，部分启动失败清理本轮进程。`down/status/logs` 不依赖私有配置和远程连通。
- 三个 HTTP 后端健康检查、Worker 启动日志和 Web 首页分别作为就绪门槛；不据此宣称推荐
  非空、Agent 无降级或历史媒体问题已解决。命令及故障语义见
  [Windows 启动说明](../../deploy/remote/README.md#windows-一键启动与停止)。

验证命令：`python -m unittest discover -s deploy/remote -p 'test_*.py' -v`，11 项中
10 项通过、1 项 POSIX Shell 用例按平台跳过。包括原生子进程创建/停止、PID 复用保护、
停止失败记录保留，以及缺配置时的停止路径。真实 `up` 构建成功并启动全部五个进程，
就绪检查全部通过；用户随后执行关闭，复查 `status` 全为 `STOPPED`，四个监听端口均释放，
进程记录为 `{}`。本轮保持用户关闭后的状态，没有再次启动；`restart` 尚无独立真实验收。

## 2026-10-25：提交前评审

复核当前启动、打包与校验修改：V1–V16 与 `main` 的原路径
`platform/persistence/src/main/resources/db/migration` 逐文件字节相同。
修复 Windows 子进程已创建、但身份读取失败时未进入清理记录的泄漏；通过原始
`Popen` 句柄停止未登记进程，已登记进程继续使用身份校验和失败记录保留。

验证：`mvn -B -ntp -pl apps/agent-server -am test` 通过；
`python -X utf8 -m unittest discover -s deploy/remote -p 'test_*.py' -v`
覆盖原生进程停止及身份读取失败清理。Windows 原生停止测试需在允许终止自身测试
子进程的环境运行。此前真实服务联调证据仍为 2026-10-20，本轮没有重新启动远程服务。

## 当时的下一步与当前路线

Step 0 完成后，当时的下一步是 Step 1“内容登记与画像发布”，该切片现在已经完成。项目随后也已完成 Step 2 关键词搜索、Step 3 Feed 基线、Step 4 Agent-ready Direct Search 和 Step 5 Agent Runtime MVP。

当前不要从本文最后一段推断下一任务；真实阶段和下一步始终以[学习路线首页](README.md)为准。
