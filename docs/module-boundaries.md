# 模块边界

## 依赖方向

```text
apps -> contexts + platform
platform -> context output ports（实现阶段按需依赖）
context adapter -> application -> domain
application -> port/in + port/out + domain
domain -> JDK only
```

`contexts` 之间不得共享数据库 Entity 或直接访问对方存储。跨上下文协作使用输入 Port、版本化 DTO 或领域事件。

## Context 内部目录

```text
domain/       聚合、实体、值对象、领域服务和领域事件
application/  用例编排、事务边界
port/in/      输入用例接口
port/out/     存储、检索、消息、模型和时钟等输出接口
adapter/in/   HTTP、Kafka Consumer、Job、管理命令
adapter/out/  PostgreSQL、Redis、Kafka、Elasticsearch、S3、模型服务
```

`domain` 不允许依赖 Spring、Reactor、Kafka、Redis、Elasticsearch、Flink 或模型 SDK。响应式类型停留在 Adapter/Application 边界，不进入领域对象。

## 物理部署基线

| 进程 | 首期装配内容 |
| --- | --- |
| online-server | Search、Recommendation、Interaction、UserInterest、Ranking、Experiment、Moderation |
| content-server | Content、Moderation、Feature/Model 控制面 |
| worker-runner | 内容理解、索引发布、特征写入、Outbox 消费 |
| realtime-features | Flink 行为清洗、会话化、窗口聚合、短期兴趣 |
| training-runner | 样本生成、基线训练、离线评测、模型注册 |

## 拆分触发条件

只有满足至少一项时才考虑拆出独立服务：需要独立扩缩容、需要不同资源类型、发布节奏冲突、故障隔离不足，或团队所有权已经独立。拆分前先确保 Port 和契约稳定。

