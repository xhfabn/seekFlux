# ADR-020 通用 Agent 评测模块

- 日期：2026-10-25
- 状态：接受，1.0 首版实现
- 关联：[模块边界](../module-boundaries.md)、[评测协议](../../contracts/runtime/agent-evaluation-v1.md)

## 背景

搜索评测依赖业务应用和数据集，Runtime 测试分散在能力包。其他 Agent 需要复用执行、故障、证据与评分流程，业务数据和指标应通过扩展接入。

## 决定

新增独立版本 agent-runtime-eval 与 agent-runtime-eval-junit，依赖为 eval-junit → eval → runtime-core，内核不反向依赖评测。SDK 不依赖业务模块、Spring 或 JUnit。

统一 Suite/Case/Check/Fault/Evidence/Report 协议。EvaluationTarget 负责目标生命周期，RuntimeFixture 提供每个用例的新工具、Planner、策略和效果快照，Grader 提供业务判断。默认目标驱动生产 Runtime 和隔离内存夹具。

工具故障装饰器完整保留原工具元数据，Schema 和策略继续由 Runtime 校验。故障命中是通过门槛；副作用检查依赖真实夹具状态。普通异常不等同于进程崩溃。

结果区分 PASS、FAIL、ERROR，所有失败保留分母。缺证据、缺能力、超时和评分失败不能冒充通过。取消 Future 后还需检查实际工作线程退出，未退出时终止后续用例。

保存完整事件、消息、Trace、效果快照和版本化评分，支持逐用例对比与离线重评分。硬约束合取作为门禁，不以综合分抵消错误。

## 边界

首版提供进程内 Runtime 一致性和通用扩展流程。持久等待、进程重启、Checkpoint 恢复、分布式 fencing、上下文压缩和在线模型 Judge 需要后续独立场景与证据，当前适配器不声明这些能力。

Java 进程内执行不能强杀不响应中断的用户代码；外部不受控操作应由目标使用独立进程隔离。本轮作为 Step 07 平台化扩展，不改变产品主路线。

