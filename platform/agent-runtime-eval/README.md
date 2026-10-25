# 通用 Agent 评测模块 1.0

独立 Java SDK，提供用例执行、通用夹具、故障注入、证据收集、可插拔评分、报告、版本对比和离线重评分。默认套件实际驱动 Agent Runtime，不需要搜索服务、Qilin、数据库或模型密钥。

当前版本为 `1.0.0-SNAPSHOT`，依赖方向为 `eval-junit → eval → runtime-core`。SDK 生产依赖没有 Spring、JUnit 或 SeekFlux 业务模块；Python 仅作为仓库内启动入口。组件尚未发布到 Maven Central。

详细解释见 [Agent 评测模块设计、执行流程与使用指南](GUIDE.md)，包含组件职责、完整流程、故障注入机制，以及工具和业务 Agent 的接入示例。

## 查看现成报告

本轮报告位于仓库 `.local/evals/agent-v1/`：

- `baseline/index.html`：17 个默认场景全部通过；可筛选、展开断言与原始证据。
- `candidate/index.html`：故意把工具答案改错的负向对照，15 个通过。
- `comparison.json`：检测到 `lookup` 与 `policy-modify` 两个回归。
- 两个目录中的 `report.json` 与 `cases.jsonl`：机器可读完整报告。
- `suite.json`：本次使用的完整套件。

负向对照是演示夹具，未修改生产 Runtime 或用例期望。报告的 PASS 表示符合场景预期，预期拒绝、取消、降级也可以 PASS，不等于真实模型任务成功率。

## 运行入口

以下命令用于后续运行；本轮按用户要求不再追加测试。

```powershell
mvn -B -ntp -pl platform/agent-runtime-eval-junit -am install
python -X utf8 evals/run_runtime_eval.py demo
```

要求 JDK 21、Maven 3.9+、Python 3。Python 入口构建并安装本地组件、生成运行依赖路径；复用已有构建可加 `--no-build`。

```powershell
python -X utf8 evals/run_runtime_eval.py --no-build run .local/evals/agent-v1/suite.json .local/evals/custom
python -X utf8 evals/run_runtime_eval.py --no-build compare .local/evals/agent-v1/baseline/report.json .local/evals/agent-v1/candidate/report.json
python -X utf8 evals/run_runtime_eval.py --no-build rescore .local/evals/agent-v1/suite.json .local/evals/agent-v1/baseline/report.json .local/evals/rescored
```

`run/rescore` 全通过退出 0，否则 1；`compare` 有新回归退出 1；参数非法或报告不可比退出 2。`demo` 要求基线全通过且负向对照出现回归。无新增回归不等于候选全部通过，发布门禁应同时检查 run。

## 默认场景

[17 个场景](src/main/resources/agent-eval/default-suite.json) 覆盖：直接完成、读取、幂等写入、参数拒绝与修复、策略拒绝与改参、允许工具范围、并行工具、调用上限、调用前故障、写入后响应丢失、超时、模型故障、模型前取消、工具前取消和澄清。

每个用例重新创建内存工具和状态。默认断言检查输出与真实效果快照，并核对事件、完整消息和 Trace：终态一致、事件唯一且递增、运行身份一致、工具调用与结果配对、工具名称与冻结 Schema 一致。usage 未测量时顶层 Token 和成本为 null。

## 新工具接入

工具无需编写故障分支。提供合法参数、期望输出，以及每次创建隔离工具的工厂：

```java
var suite = ToolContractSuite.create(
    "convert", Map.of("text", "hello"), Map.of("result", "HELLO"));
var target = ToolContractSuite.target("my-agent", "build-123", MyIsolatedTool::new);
var report = EvalCli.runner().run(suite, target);
ReportWriter.write(Path.of("target/evaluation"), report);
```

自动生成成功、调用前故障、调用后故障、调用前取消四类用例，均经过 Runtime 注册、允许范围、Schema、策略和执行路径。[完整示例](src/test/java/io/seekflux/platform/agenteval/CustomToolContractTest.java) 同时验证故障前不会执行工具体。

快捷模板接受 READ_ONLY 或 IDEMPOTENT 工具。业务不变量和外部副作用事实由业务夹具提供；MUTATING 工具需要具备持久账本的目标适配器，快捷模板明确拒绝。

## 完整 Agent 与业务评分

实现 `RuntimeFixture.Factory`，为每个用例提供新的工具、AgentPlanner、AgentDefinition，以及可选的执行策略、效果快照和清理逻辑；`RuntimeEvaluationTarget` 自动装配生产 Runtime、统一故障控制与证据记录。

远程服务或其他执行入口实现 `EvaluationTarget`，提供 `open → execute → close`。capabilities 声明实际支持的能力；缺少用例必需能力时直接记 ERROR，不执行目标。

实现 `Grader` 可增加领域评分，必须提供 ID、版本与非空 findings。自定义目标可使用 `new EvaluationRunner(List.of(new ChecksGrader(), businessGrader))`；只有提供 Runtime 完整证据的目标才加入 RuntimeInvariantsGrader。评分异常和空评分记 ERROR。

`rescore` 使用已有证据重新评分，不调用目标或工具。要求原 Suite Hash 相同，保留原执行错误与故障命中事实；Java SDK 可替换 Grader 版本。CLI 使用内置评分器。

JUnit 适配器提供 `AgentEvaluations.tests(report)`，可在 `@TestFactory` 中返回，每个用例变为独立测试结果。[默认接入](../agent-runtime-eval-junit/src/test/java/io/seekflux/platform/agenteval/junit/DefaultRuntimeSuiteTest.java) 会将报告写入 target/evaluation。

## 故障和预算

故障按当前用例、阶段、工具名、第 N 次匹配定位，支持 BEFORE_MODEL、AFTER_MODEL、BEFORE_TOOL、AFTER_TOOL，以及 THROW、DELAY、CANCEL。每条规则必须恰好命中一次，否则 FAIL。并发同名工具的计数按到达顺序；要求固定调用位置时使用串行场景。

工具装饰器保留元数据，位于 Runtime Schema 和策略检查之后。策略拒绝不会进入工具体，因而不会命中工具体故障；拒绝场景应断言未执行。

用例超时覆盖创建、执行、清理和评分，与 Runtime Deadline 独立。预期 Runtime 超时降级可以通过，外层 CASE_TIMEOUT 为 ERROR。取消后最多再等候 250ms 检查实际线程退出；未退出则后续用例保留为 ERROR 并停止启动。进程内 Java 无法强杀不响应中断的外部操作，此类目标应采用独立进程隔离。

## 1.0 边界

当前交付覆盖通用执行与扩展机制、进程内故障、固定 Runtime 套件和报告。真实进程崩溃、Checkpoint 持久恢复、等待审批回调、分布式 fencing、上下文压缩专用套件、在线模型 Judge、多轮数据适配仍待后续切片；当前适配器不声明这些能力。

协议见[评测契约](../../contracts/runtime/agent-evaluation-v1.md)，架构见 [ADR-020](../../docs/adr/ADR-020-generic-agent-evaluation.md)，长期范围见[规划](../../evals/agent-evaluation-plan.md)。

