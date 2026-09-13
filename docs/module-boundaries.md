# 模块边界

## 依赖方向

```text
apps -> contexts + platform
apps -> Context/Runtime 的接口与实现（只负责选择和装配）
context infrastructure -> context application/port + Runtime API/SPI + 被适配 Context 输入 Port
platform infrastructure -> platform application SPI
platform -> context output ports（实现阶段按需依赖）
context adapter -> application -> domain
application -> port/in + port/out + domain
domain -> JDK only
```

`contexts` 之间不得共享数据库 Entity 或直接访问对方存储。跨上下文协作使用输入 Port、版本化 DTO 或领域事件。

当前仓库有十个业务 Context，`AgentOrchestration` 是第十个；`platform/agent-runtime` 是横向执行基础设施，不是业务 Context。`feature-context` 是既有 Feature Context 在 Step 9 的具体实现，不新增第十一个 Context。Step 5 已经实现 Agent 业务语义与运行机制的分离，后续新增能力仍必须保持这一边界。

## Context 内部目录

```text
domain/       聚合、实体、值对象、领域服务和领域事件
application/  用例编排、事务边界
port/in/      输入用例接口
port/out/     存储、检索、消息、模型和时钟等输出接口
adapter/in/   HTTP、Kafka Consumer、Job、管理命令
adapter/out/  PostgreSQL、Redis、Kafka、Elasticsearch、S3、模型服务
```

`domain` 不允许依赖 Spring、Reactor、Kafka、Redis、Elasticsearch、Flink 或模型 SDK。输入 Port、输出 Port 和应用服务使用普通 Java 返回值；异步并发只允许在明确的编排点通过命名且有界的执行器引入，不成为跨层接口类型。

## Agent 专用依赖规则

`platform/agent-runtime` 是供业务 Context 接入的框架内核，因此不用普通 Context 的 `port/in|out` 命名，而把 `application` 明确定义为纯对外契约面：

```text
业务 / interfaces -> application/api <- domain/service
输入请求          -> application/command
domain/service    -> application/spi/business   <- 业务定制实现
domain/service    -> application/spi/capability <- 基础设施或业务 Adapter
domain/service    -> domain/model
```

API 与 SPI 都是对外契约。API 表示“业务可以调用 Runtime 什么”，Command 表示“业务向 Runtime 传入什么”，SPI 表示“业务或运行环境需要向 Runtime 提供什么”。Application 不放 Service 或通用 Model；组件状态与规则进入 `domain/model`，跨组件关系与主链编排进入 `domain/service`。默认基础设施实现可以被替换、装饰或组合。

```text
apps/agent-server
  -> interfaces/rest + bootstrap
  -> contexts/agent-orchestration-context port/in
  -> contexts/agent-orchestration-context infrastructure
  -> platform/agent-runtime application/api

contexts/agent-orchestration-context
  domain/application/port -> 自身业务规则与契约
  infrastructure -> 自身 port/out + Runtime application/api/business SPI/capability SPI + Search port/in + OpenAI-compatible 协议
  domain/application/port -/> Runtime 具体实现、Redis、Micrometer

platform/agent-runtime
  domain/application -> LLM / Session / RuntimeEvent / Clock 等 capability SPI
  infrastructure -> 自身 application/spi + Runtime 所有的纯 Java/Redis 默认实现
  domain/application/infrastructure -/> AgentOrchestration domain、Search domain、Elasticsearch
```

AgentOrchestration 的 Domain/Application 决定搜索目标、约束修正、追问和业务回退；Runtime 的 Domain/Application 只执行有限步循环、Deadline、取消、Tool 调度与运行事件。跨 Context 适配发生在 `agent-orchestration-context/infrastructure`：`SearchDirectTool` 与确定性回退都调用 Search 的稳定输入 Port。最终实现选择发生在 `agent-server/bootstrap`，但 Server 不拥有 Adapter 实现。Direct Search 不反向依赖 Agent 模块。

## 物理部署基线

| 进程 | 首期装配内容 |
| --- | --- |
| online-server | Search、Recommendation、Interaction、UserInterest、Ranking、Experiment、Moderation |
| content-server | Content、Moderation、Feature/Model 控制面 |
| worker-runner | 内容理解、索引发布、行为事实写入、特征写入、Outbox Relay 与 Agent 终态幂等审计消费 |
| agent-server | Agent REST API 与组合装配；依赖 AgentOrchestration 和 Runtime 模块，端口 `8083`，独立线程池和故障边界 |
| realtime-features | 生产 Flink 事件时间窗口、短期兴趣、内容热度与迟到事件输出；本地统一启动由 Worker JDBC 参考投影器执行同一策略 |
| multimodal-model（Step 11） | SigLIP 共享向量、FFmpeg 关键帧、RapidOCR、faster-whisper 与可选 BLIP；只经 MediaEmbeddingPort/MediaUnderstandingPort 被 Java 应用调用 |
| training-runner（Step 12 可选） | 样本生成、基线训练、离线评测、模型注册 |

## 拆分触发条件

只有满足至少一项时才考虑拆出独立服务：需要独立扩缩容、需要不同资源类型、发布节奏冲突、故障隔离不足，或团队所有权已经独立。拆分前先确保 Port 和契约稳定。
