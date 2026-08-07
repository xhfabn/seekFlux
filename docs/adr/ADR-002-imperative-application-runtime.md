# ADR-002：命令式应用运行模型与局部有界并发

- 状态：Accepted
- 日期：2026-08-08

## 背景

项目早期同时使用 WebFlux、Reactor、R2DBC、Reactive Redis 和 WebClient，导致 `Mono` 从 HTTP Adapter 贯穿应用服务和输出 Port。当前业务规模不需要端到端响应式运行模型，这种统一异步化反而增加断点调试、异常定位、事务边界和 Worker 编排的理解成本；部分 Worker 最后还通过 `block()` 恢复同步语义。

## 决策

1. HTTP 服务使用 Spring MVC，Controller、输入 Port、输出 Port 和应用服务返回普通 Java 对象。
2. PostgreSQL 使用 JDBC、HikariCP 和 Spring 本地事务；内容聚合与 Outbox 事件继续在同一事务中提交。
3. Redis 使用 `StringRedisTemplate`，Elasticsearch 使用 `RestClient`，Adapter 对外暴露同步方法。
4. Kafka Listener 与 Outbox Relay 保持事件驱动，但不把响应式类型引入业务接口。
5. 只有存在明确并发扇出收益的推荐召回使用 `CompletableFuture`，并固定运行在命名、有界、可配置的线程池中。每个下游同时配置请求超时和召回超时；禁止使用公共线程池或无界队列。
6. 新代码不得在领域、应用、Port 或 Controller 中引入 `Mono`、`Flux`、`block()`，除非先通过新的 ADR 说明必须采用端到端响应式运行模型的证据和边界。

## 结果

- 调用链、异常传播、事务和断点调试恢复为普通 Java 语义。
- 内容写入与 Outbox 的原子性、乐观锁、Kafka 至少一次投递和消费者幂等规则保持不变。
- 推荐仍能并行执行热门、兴趣、相似三路召回，并在单路失败或超时时降级。
- Spring Data Redis 使用的 Lettuce 可能在内部携带 Reactor 传递依赖，但项目代码不使用或暴露响应式 API；该内部实现不改变本 ADR 的应用运行模型。
- 同步 I/O 的容量上限由 Tomcat、HikariCP、下游连接和有界召回线程池共同决定，需要监控活动线程、队列深度、拒绝次数、连接池等待和下游延迟。
