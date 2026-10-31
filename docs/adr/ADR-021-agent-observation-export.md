# ADR-021 Agent 执行观测与可替换导出

- 日期：2026-10-30
- 状态：接受；源码已实现，运行验收未执行
- 关联：[模块边界](../module-boundaries.md)、[事件契约](../../contracts/runtime/agent-observation-v1.md)

## 背景

Runtime 已有运行事件、工具 observer、实时 Push 和最终 Trace，但缺少面向模型/上下文/工具的统一输入输出观测协议。外部平台字段不应侵入业务 Tool，也不能让观测平台故障改变恢复和副作用语义。

## 决定

Core 定义 AgentObservationEvent、Observer、Processor、Exporter SPI。AgentRuntime 在统一生命周期和工具入口采集；DefaultAgentLoop 自动装饰上下文与模型，独立模型调用和规划阶段不混淆。保持原 Recorder、Push 和持久化接口兼容。

事件先限制内容并快照，再经本地 Processor 过滤/脱敏，最后异步交给 Exporter 转换格式。默认不开启正文或外部发送。导出适配器不引入 Langfuse、OTel、HTTP 客户端等 Core 强依赖。

默认异步传输使用有界内存队列、批次重试、稳定事件 ID 和限时关闭。队列满/重试耗尽可丢弃并计数，不能替代 durable outbox。自定义 observer/processor 的同步阻塞仍由实现者负责。

Spring 只装配依赖与配置，不通过 BeanPostProcessor 改变业务 Bean 类型。默认 Loop 直接在调用边界装饰，覆盖 Map 内部的客户端。宿主自建 Runtime 需注入 AgentObservations，当前 agent-server 已接入。

## 结果与边界

接口使上报格式由宿主决定，业务工具无需修改。模型流式、普通/提前/恢复工具与对账有独立语义；停止等待不等于底层工作停止，观测允许晚到。

一次逻辑模型调用不等于每次服务商 HTTP 调用。自定义 Loop、内部额外客户端、跨进程子 Agent 传播需要显式接入。内容快照经过截断和脱敏，不能据此宣称可无损重放。

本轮仅通过生产源码编译，未运行自动化测试或固定评测，不改变阶段完成状态。使用说明见 [观测指南](../../platform/agent-runtime/OBSERVABILITY.md)。
