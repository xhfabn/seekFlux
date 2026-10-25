# Agent 评测协议 v1

对应 `seekflux-agent-runtime-eval:1.0.0-SNAPSHOT` 的 Evaluation records；不改变 Runtime 的业务 API、事件或表结构。

## 输入

Suite 包含非空 id/version、metadata、非空 cases。Case 包含唯一 id、input、requiredCapabilities、目标专用 JSON parameters、faults、非空 checks 和 1–300000 毫秒 timeoutMillis。集合字段必须提供，可为空。重复用例、断言或故障 ID 拒绝加载。

Check 的 path 是以 Evidence.data 为根的 JSON Pointer；operator 支持 EQUALS、EXISTS、SIZE、CONTAINS、AT_MOST、AT_LEAST。缺少必需路径记 ERROR，值不匹配记 FAIL；EXISTS 对缺失记 FAIL。数值比较忽略整数与浮点表示差异。

Fault 包含 id、phase、tool、occurrence、action、delayMillis。tool 为 null 时不限制工具名，occurrence 从 1 开始独立计数，DELAY 范围为 1–60000 毫秒。支持阶段和动作见[模块说明](../../platform/agent-runtime-eval/README.md#故障和预算)。

## 执行和判定

每个 Case 创建独立 Session，执行 open、execute、close，再评分；共享用例时限。Runner 顺序执行用例，Runtime 适配器内部最多四个执行任务并发。

必需 capability 缺失不启动目标并记 ERROR。创建、执行、清理、评分错误及超时均记 ERROR；空评分也记 ERROR。故障规则没有恰好命中一次记 FAIL。优先级为 ERROR > FAIL > PASS，没有隐式跳过或用综合分抵消硬约束错误。

取消后检查实际执行线程退出；未退出时，后续用例记 RUN_ABORTED 并停止启动。所有失败保留在报告分母。

## 报告与证据

Report.schema 为 agent-eval/v1，包含 runId、UTC createdAt、suiteId/version/hash、target/version、metadata、Grader ID/版本和完整 results。Suite Hash 是字段有序 JSON 的 SHA-256；capability 集合排序，case 顺序保留，执行时刻和 UUID 不参与 Hash。

CaseResult 包含 caseId、status、tookMillis、findings、evidence、faultHits。Runtime evidence.data 保留 state、output、clarification、fallbackReason、cancellationReason、完整 messages/events/trace、effects、modelCalls、providerUsageMeasured、totalTokens 和 costMicros。未测量 usage 时顶层 Token/成本为 null，原 Trace 保留自身 measured 标记和原字段。

AFTER_TOOL 表示工具体返回后、Runtime 接收结果前。它不表示进程终止、事务已提交或持久副作用恰好一次。effects 来自夹具探针，外部持久事实须由目标读取。

## 重评分和比较

Rescore 要求相同 Suite Hash 和完整有序用例，只重做评分，保留执行错误与命中事实；有用例级评分时限，rescoredFrom 关联原报告。

Compare 要求相同 suiteId/version/hash、target 标识、Grader ID/版本和用例集合，targetVersion 可变化。输出 regressions、improvements、通过数；原有失败不算新增回归。无回归不代表所有用例通过。

JSON/JSONL 是机器接口，HTML 展示同一结果。报告包含完整证据，应按评测输入相同的数据访问范围保存和共享。固定场景通过率不代表真实模型业务成功率。

