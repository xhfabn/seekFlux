# Agent 统一观测验收清单

2026-10-30：生产源码已实现并编译。按用户“先不测试”的要求，以下运行验收均未执行；没有新的分数、通过率或观测基线。

## 已有编译证据

`mvn -o -pl apps/agent-server -am compile` 已通过，覆盖 Core、自动配置、当前业务宿主及依赖模块。仅编译生产源码，不等同于下表通过。

## 待执行验收

| 场景 | 应核验的证据 |
| --- | --- |
| 默认关闭 | 原有执行结果不变，没有外部输出 |
| 普通模型与工具 | RUN/PLAN/CONTEXT/MODEL/TOOL 的父子 ID、参数和结果对应 |
| 正文开关 | 关闭时不携带 input/output/errorMessage，开启后按限制快照 |
| 嵌套可变数据 | 入队后修改原始集合不改变已捕获事件；敏感字段已处理 |
| Schema/策略拒绝 | 有准备阶段失败，没有伪造工具体已执行 |
| 策略修改参数 | 原参数、实际参数与实际工具调用一致 |
| 工具失败 | success=false 与抛异常可区分；只读异常转换前有事件 |
| 并行/EAGER/RECOVERY | ID 不串线，恢复与普通调用来源可分辨 |
| 对账 | 有 RECONCILIATION，未盲目重复执行写工具 |
| 超时与取消 | 记录停止等待，晚到结果不伪装为超时前完成 |
| 流式部分失败 | 已输出块数、用量和失败原因保留；终态不重复 |
| 溢出重试与 Shadow | 独立 MODEL，shadow 不误计入主链路用量 |
| 队列满/目标不可达 | 业务不等待网络；丢弃、重试计数与实际一致 |
| 关闭 | 在配置期限内返回；忽略中断的 exporter 不能阻塞主进程退出 |
| Spring 接入 | 默认装配和 agent-server 自建 Runtime 均收到同一观测依赖 |
| 接口兼容 | 旧构造器/Recorder/ToolExecutionObserver/Push 不改变持久化语义 |

后续应以确定性测试和固定故障场景完成验收，不能只观察控制台出现 JSON 就认为全部通过。事件缺失、截断或上报丢弃时，评测器应表达证据不完整。

[观测指南](../platform/agent-runtime/OBSERVABILITY.md) · [事件契约](../contracts/runtime/agent-observation-v1.md) · [学习入口](../docs/learning/README.md)
