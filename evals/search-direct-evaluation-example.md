# SearchDirectTool 评测示例

本示例演示如何把已有业务工具接入通用评测模块：生产 SearchDirectTool 和 AgentRuntime 保持不变，以固定 SearchUseCase 替代真实搜索后端，再配置输入、故障和预期。

当前仅交付示例源码与用例。按用户要求，尚未编译或运行，也未生成本示例的报告；表中的结果是期望值，不是实测结果。

## 先看这三个文件

| 文件 | 作用 |
| --- | --- |
| [SearchDirectEvaluationTest.java](../agent/src/test/java/io/seekflux/agent/evaluation/SearchDirectEvaluationTest.java) | 装配被测目标、执行套件、保存报告并转为 JUnit 用例 |
| [search-direct-v1.json](../agent/src/test/resources/evals/search-direct-v1.json) | 六条用例的输入、故障规则与结果断言 |
| [SearchDirectTool.java](../agent/src/main/java/io/seekflux/agent/orchestration/adapter/SearchDirectTool.java) | 实际被测的生产工具，没有加入评测专用分支 |

接入依赖只放在 agent 模块的 test scope。业务测试依赖通用评测 SDK，通用 SDK 不反向依赖搜索业务。

## 一个用例如何运行

```text
加载 search-direct-v1.json
    ↓
每个 Case 新建 SearchFixture
    ├─ 真实 SearchDirectTool
    ├─ 固定 SearchUseCase：记录入参，返回固定结果或抛业务异常
    └─ 脚本 Planner：按 plan 发起调用，再按工具结果完成或降级
    ↓
RuntimeEvaluationTarget 自动包裹故障装饰器
    ↓
真实 AgentRuntime 校验 Schema、策略并调用工具
    ↓
读取 effects：搜索服务调用次数、实际 SearchQuery
    ↓
通用 Check + Runtime 事件/消息/Trace 一致性检查
    ↓
报告文件 + 每个 Case 对应一个 JUnit 动态测试
```

这份示例验证工具执行契约，不评测自然语言理解或真实检索相关性。固定后端始终返回预设候选；标签场景检查标签是否传递给搜索服务，不证明 Elasticsearch 实际完成了过滤。

## 六个场景分别检查什么

| 用例 ID | 操作 | 期望终态/原因 | 搜索服务调用次数 |
| --- | --- | --- | --- |
| defaults-and-result | 只传 query | RESULTS_READY；默认 page=0、size=12、标签为空；候选和 Trace 正确关联 | 1 |
| explicit-pagination-and-tags | 显式传 page、size、required_tags | RESULTS_READY；SearchQuery 收到指定分页与标签 | 1 |
| missing-query | 缺少必填 query | FALLBACK_REQUIRED / TOOL_ARGUMENT_INVALID | 0 |
| fault-before-tool | BEFORE_TOOL 注入 THROW | FALLBACK_REQUIRED / TOOL_EXECUTION_FAILED | 0 |
| fault-after-tool | AFTER_TOOL 注入 THROW | FALLBACK_REQUIRED / TOOL_EXECUTION_FAILED | 1 |
| search-service-unavailable | SearchUseCase 抛 SearchUnavailableException | FALLBACK_REQUIRED / SEARCH_UNAVAILABLE | 1 |

其中三个失败场景的差别很关键：

- 调用前异常发生时，工具体和搜索服务都没执行。
- 调用后异常发生时，搜索服务已经执行，结果在返回 Runtime 前丢失。
- 搜索服务不可用发生在工具内部依赖边界，用于验证 SearchDirectTool 自己的异常映射。

前两种故障由通用 FaultController 注入；第三种由业务测试替身提供。不能用工具体外的 THROW 代替业务异常映射验证。

## 故障规则写在哪里

用例 JSON 的 faults 中配置，例如：

```json
{
  "id": "injected-tool-failure",
  "phase": "BEFORE_TOOL",
  "tool": "search_direct",
  "occurrence": 1,
  "action": "THROW",
  "delayMillis": 0
}
```

这表示当前用例第一次进入 search_direct 工具体前抛异常。每个 Case 有自己的控制器与计数，多个用例可以复用同一个故障 ID。框架还会核对该规则是否恰好命中一次。

## 业务事实如何进入报告

SearchFixture 中的固定搜索实现先记录实际 SearchQuery，再返回数据或抛出业务异常。effects() 返回：

```json
{
  "searchCalls": 1,
  "requests": [
    {
      "text": "露营",
      "page": 1,
      "size": 5,
      "requiredTags": ["亲子", "周末"],
      "userId": "anonymous"
    }
  ]
}
```

因此，检查 /effects/searchCalls 为 0，验证的是搜索依赖实际上没有被调用，而不是根据最终降级状态猜测。正常结果还检查 /output/searchResult/hits/0/contentId，以及 ToolResult 的 linkedTraceId。

固定候选为 camp-001，固定搜索 Trace 为 search-eval-trace-v1。固定 ID 用于使本例断言可重复；接入真实搜索时，应检查 Trace 之间的关联关系，避免要求随机 ID 或耗时完全等于固定值。

## 后续运行方法

在仓库根目录执行以下命令，仅选择该示例测试；当前交付未执行此命令：

```powershell
mvn -B -ntp -pl agent -am test '-Dtest=SearchDirectEvaluationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

Maven 会构建上游模块，选中的示例使用内存状态，不启动 Spring、Elasticsearch、Redis 或 PostgreSQL，也不调用模型。测试定义需要 JDK 21。

成功执行到报告写入后，输出目录为：

```text
agent/target/evaluation/search-direct-v1/
├── suite.json
├── report.json
├── cases.jsonl
└── index.html
```

报告先写入，再将结果转换为 JUnit 动态测试；断言失败时也可以查看已经保存的证据。若发生编译、资源加载或报告写入错误，则不保证生成报告。

## 从示例迁移到自己的工具

保留加载套件、创建 RuntimeEvaluationTarget、调用 Runner、保存报告和转换 JUnit 的代码。需要按业务替换的是工具构造、测试依赖、效果探针和 JSON 中的输入/期望。

仅需要四类基础契约时，可以直接使用 ToolContractSuite；本例为了展示参数传递、调用次数和业务异常映射，使用显式用例与自定义 Fixture。

通用流程详见[模块指南](../platform/agent-runtime-eval/GUIDE.md)，完整字段约定见[评测协议](../contracts/runtime/agent-evaluation-v1.md)。
