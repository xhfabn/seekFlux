# Agent Runtime 统一观测与数据上报

当前开发源码提供统一观测事件、自动阶段采集、可配置的正文采集、入队前处理及有界异步导出。默认关闭。用户通过 Exporter 决定格式与目的地，业务 Tool 不需要添加日志或上报分支。

本轮已完成生产源码编译，未运行测试或真实导出验收；不代表已有评测基线发生变化。

## 1. 执行流程

```text
AgentRuntime：RUN → PLAN → TOOL_PREPARATION → TOOL / RECONCILIATION
                      └─ DefaultAgentLoop：CONTEXT → MODEL
                                           └─ 溢出重试产生独立 MODEL
    ↓
AgentObservations：身份、时间、结果、异常
    ↓
ObservationCapturePolicy：正文开关、字段脱敏、深度/节点/字符限制、不可变快照
    ↓
AgentObservationProcessor：用户筛选或额外脱敏
    ↓
重新限制处理后的 data，保持事件身份和生命周期不变
    ↓
AgentExecutionObserver
    ↓ 默认实现
AsyncObservationExporter：有界队列 → 批量 → 有限重试 → 限时关闭
    ↓
用户的 AgentObservationExporter：自定义格式、文件、HTTP 或其他平台
```

同一套事件同时支持排查与后续评测适配，但本轮没有自动把这些事件转换成 Langfuse/OTel 协议，也没有新增模型裁判。

## 2. Spring Boot 接入

使用当前开发版 `agent-runtime-spring-boot-autoconfigure`，配置：

```yaml
seekflux:
  agent:
    runtime:
      observation:
        enabled: true
        capture-content: true
        queue-capacity: 2048
        batch-size: 64
        max-attempts: 3
        max-characters: 16384
        max-nodes: 512
        max-depth: 12
        shutdown-timeout: 5s
        redacted-fields:
          - authorization
          - password
          - secret
          - apiKey
          - api_key
          - access_token
```

`capture-content` 默认 false，保留身份、阶段、结果、用量等元数据，但 input/output/errorMessage 显示为 `[not captured]`。需要评测实际内容时显式开启。字段名脱敏不能识别自然语言中的电话号码、地址等，需要自己的 Processor。

提供一个 Exporter Bean 即可。下面示例使用宿主已有的 Jackson ObjectMapper，将字段转换为自定义 JSONL；文件写入只发生在导出线程：

```java
@Bean
AgentObservationExporter agentObservationExporter(ObjectMapper mapper) throws IOException {
    Path file = Path.of("logs", "agent-observations.jsonl");
    Files.createDirectories(file.getParent());
    return events -> {
        StringBuilder batch = new StringBuilder();
        for (AgentObservationEvent event : events) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("schemaVersion", event.schemaVersion());
            row.put("id", event.eventId());
            row.put("run", event.identity().agentRunId());
            row.put("operation", event.identity().observationId());
            row.put("parent", event.identity().parentObservationId());
            row.put("identity", event.identity());
            row.put("kind", event.kind());
            row.put("phase", event.phase());
            row.put("name", event.name());
            row.put("time", event.occurredAt());
            row.put("durationNanos", event.durationNanos());
            row.put("outcome", event.outcome());
            row.put("data", event.data());
            batch.append(mapper.writeValueAsString(row)).append("\n");
        }
        Files.writeString(file, batch, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    };
}
```

SPI 位于 `application.spi.capability.observation`。示例还需导入 java.nio.file、java.nio.charset、java.util、java.io、Spring Bean 和宿主 ObjectMapper。

生产 Exporter 应自己配置网络超时；文件示例没有轮转、跨进程互斥或 exactly-once 保证。如果写到一半抛异常，重试可能重复部分行，按 eventId 去重。

支持多个 Exporter Bean，按 Spring 顺序调用。任一目标失败会重试整个批次，已成功的目标可能再次收到相同事件。

关闭配置或没有 Exporter 时默认不导出。自定义 AgentExecutionObserver Bean 可覆盖默认队列实现，但其 onEvent 必须快速返回；框架不能强制中断用户在执行线程上的阻塞代码。

### 当前仓库应用

`apps/agent-server` 自己创建 AgentRuntime，已经注入同一个 AgentObservations Bean。它通过 Map 管理模型客户端，默认 Loop 会在真实调用位置装饰客户端，不依赖客户端是否单独注册为 Spring Bean。

没有替换业务 Tool 或 LlmClient Bean 的类型，也不要求修改业务工具。自动配置默认 Runtime 和应用自建 Runtime 都保留旧构造器兼容。

## 3. 入队前筛选与额外脱敏

可以提供一个 `AgentObservationProcessor` Bean：

```java
@Bean
AgentObservationProcessor observationProcessor() {
    return event -> {
        // 示例：不采集 Shadow 模型运行。
        if (event.name().equals("llm.shadow")) return null;
        return event;
    };
}
```

返回 null 会丢弃该事件并增加 filteredCount。修改 data 时返回新的 AgentObservationEvent，框架保留原来的身份、阶段、时间和结果，只使用返回事件的 data 并再次快照。

Processor 在执行线程运行，适合轻量字段处理，不应执行网络请求。采样建议按 observationId 或 agentRunId 稳定选择，避免只留下 STARTED 或只留下终态。正文关闭时，Processor 无法重新获取已经省略的原始内容。

## 4. 不使用 Spring

```java
var policy = new ObservationCapturePolicy(
        true, 16384, 512, 12,
        Set.of("password", "authorization", "api_key"));

var transport = new AsyncObservationExporter(
        exporter, 2048, 64, 3, Duration.ofSeconds(5));
var observations = new AgentObservations(transport, policy);

var runtime = new AgentRuntime(
        tools, toolExecutor, executor, runRecorder, clock, callGuard,
        toolPolicy, legacyToolObserver, capabilityResolver, observations);

var loop = new DefaultAgentLoop(runtime, contextEngine, clock);
// 正常使用 loop；应用关闭时调用 transport.close()。
```

旧构造器默认 NOOP。DefaultAgentLoop 自动观察上下文与模型调用，包括非 Bean、Map 内部的 LlmClient。已经是 ObservingLlmClient/ObservingContextEngine 的对象不会再次包装。

自定义 Planner 或自定义 Loop 绕过 DefaultAgentLoop 时，手动使用：

```java
LlmClient observedClient = new ObservingLlmClient(client, observations);
ContextEngine observedContext = new ObservingContextEngine(engine, observations);
```

两者位于 `domain.service.observation`。这些装饰器是线程安全的，每次调用独立创建 Scope，保留原有返回结果与异常。自定义组件内部直接创建的额外客户端不会被反射发现，需要同样装饰。

## 5. 各阶段记录哪些数据

| 阶段 | 记录内容 | 语义 |
| --- | --- | --- |
| RUN | 请求、结果、累计 usage、终态/降级原因 | 一次 Runtime 执行，终态在持久化处理返回后发出 |
| PLAN | step、AgentDecision、异常或等待停止原因 | 一次规划，不等于一次模型请求 |
| CONTEXT | 输入预算、估计 token、压缩截止点、模式、组装消息 | 包括溢出回退后的重新组装 |
| MODEL | messages/tools、providerVersion、specId、结果、usage | LlmClient 逻辑调用，不等于每次 HTTP 尝试 |
| MODEL 流式 | chunkCount、firstOutputNanos、lastStreamUsage | 不逐 token 导出；失败记录已观察用量与输出块数 |
| TOOL_PREPARATION | 原参数、实际参数、是否变化、审批要求、错误 | 校验/策略拒绝不伪装成工具体执行失败 |
| TOOL | 参数、结果、schemaVersion、effect、source、错误 | 覆盖普通、EAGER、RECOVERY 的真实调用 |
| RECONCILIATION | 参数、对账结果、resolution | 外部副作用对账，独立于重复执行工具 |
| RUN 的 EVENT | 决策、工具完成、检查点、等待、能力变化等 | 从现有执行事实发出观测投影，原持久化接口不变 |

Tool 返回 success=false 时，会得到 COMPLETED + outcome=FAILED，表示调用正常返回了失败结果；抛异常使用 FAILED phase。不要只靠 phase 判断业务成功。

默认工具执行器转换只读工具异常前会记录 TOOL_EXCEPTION。自定义执行器若自行吞掉异常，只能观察它公开返回的结果；可在自定义边界调用 AgentObservations.reportCurrent 记录额外事件。

工具超时/取消会记录 TOOL_WAIT_ABANDONED 或 EAGER_CANCEL_REQUESTED，标明 executionMayContinue。工具若忽略中断，实际返回事件可能晚于 RUN 终态；若永不返回，不会伪造一个工具执行结束事件。

ShadowingLlmClient 的候选执行使用 llm.shadow 名称和独立模型 Scope；它的用量不可直接与主运行累计用量相加作为主链路费用。主运行摘要和各模型事件也不能重复求和。

流式 firstOutputNanos=-1 表示尚未观察到输出；usage.measured=false 表示未测量，不能当作零成本。失败流不保存完整的部分文本，正常完整输出由 LlmCallResult 的 assistantContent 提供。

## 6. 身份与顺序

事件携带 schemaVersion、eventId、agentRunId、requestId、sessionId、turnId、observationId、parentObservationId、toolCallId、attemptId。

- eventId 是该事件的身份；导出重试复用原对象和 ID。
- 一次 Scope 的 STARTED、EVENT 和终态共用 observationId/attemptId。
- RUN 是根，PLAN 是子节点，模型/上下文通常位于 PLAN 下。
- 普通及 EAGER 工具关联对应 PLAN；恢复执行与对账关联当前 RUN。
- 恢复时从持久化结果直接返回的场景，不伪造新工具或模型调用；resultRunId 可以指向旧运行。
- 时间戳用于展示，durationNanos 使用单调时钟；并行分支不能按事件到达顺序推断业务先后。
- 自定义异步任务不会自动继承 ThreadLocal。已内置的执行路径显式建立 Scope；额外跨进程或子 Agent 的父子传播需要宿主提供。

## 7. 异步导出的保证与限制

队列满时丢弃新事件，绝不回到业务线程同步导出。每批最多尝试 maxAttempts 次，失败后计入 dropped。关闭时停止接收并尝试排空，超过 shutdownTimeout 中断 daemon 导出线程。

Exporter 忽略中断时线程可能继续运行，不能保证强制停止。使用者仍需配置自己的 IO 超时。该实现没有磁盘缓冲，不保证进程崩溃后事件不丢失。

通过 AsyncObservationExporter.statistics() 查看 queued、inFlight、exported、dropped、failedAttempts；AgentObservations 提供 captureFailureCount()、filteredCount()。没有自动接入 Micrometer，宿主可按需要绑定这些数值。

观测是 best effort。评测消费者必须核对阶段覆盖率、截断标记、缺失终态与丢弃计数，不能把缺失证据当通过。需要可靠审计时继续使用原有 Session/Checkpoint/Journal 或另配持久 Outbox。

## 8. 源码与验证

- [事件和身份](src/main/java/io/seekflux/platform/agentruntime/application/spi/capability/observation/AgentObservationEvent.java)
- [统一采集](src/main/java/io/seekflux/platform/agentruntime/domain/service/observation/AgentObservations.java)
- [采集策略](src/main/java/io/seekflux/platform/agentruntime/domain/service/observation/ObservationCapturePolicy.java)
- [模型装饰器](src/main/java/io/seekflux/platform/agentruntime/domain/service/observation/ObservingLlmClient.java)
- [异步导出](src/main/java/io/seekflux/platform/agentruntime/infrastructure/observation/AsyncObservationExporter.java)
- [Spring 装配](../agent-runtime-spring-boot-autoconfigure/src/main/java/io/seekflux/platform/agentruntime/autoconfigure/AgentObservationConfiguration.java)
- [事件契约](../../contracts/runtime/agent-observation-v1.md)
- [待执行验收清单](../../evals/agent-observation-acceptance.md)

生产源码编译命令：

```powershell
mvn -o -pl apps/agent-server -am compile
```

按当前用户要求未运行测试、评测或服务启动验收。编译不证明并发、恢复和 Spring 生命周期已经通过运行验证。
