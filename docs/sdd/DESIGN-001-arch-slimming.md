# DESIGN-001 架构收敛技术方案

> **重做说明（2026-10-20）**：本方案基于 `9292ea8`，该点落后 `origin/main@86593ba` 15 个提交。新基线上 `platform/agent-runtime` 已有 160 个类，并新增 `platform/agent-runtime-spring-boot-autoconfigure`（Maven Central RC1 发布用），因此 D7 与 §2.1 的模块清单不再成立。重做分支 `refactor/arch-slimming-v2` 的模块目标为：`core`、`agent`（编排）、`agent-runtime`（可发布内核）、`agent-runtime-autoconfigure`、`shared`、4 个 app、pipeline。其余决策（先合模块再改包名、三段式、跨域只走 api、装配下沉、不拆 ES 适配器）继续有效。

| 项 | 内容 |
| --- | --- |
| 方案编号 | DESIGN-001 |
| 状态 | 待评审（Gate 2） |
| 对应规格 | [SPEC-001](SPEC-001-arch-slimming.md)（Gate 1 已通过） |
| 分支 | `refactor/arch-slimming`（基线 `9292ea8`） |
| 撰写日期 | 2026-10-20 |
| 后续产物 | `TASKS-001-arch-slimming.md`（Gate 3） |

---

## 1. 决策摘要

| 编号 | 决策 | 理由 | 对应需求 |
| --- | --- | --- | --- |
| D1 | 目标为 `core` + `agent` + `shared` + 4 apps + 1 pipeline，共 8 个非聚合模块 | 三块的真实边界分别是"检索业务域"、"智能体内核"、"技术共享"；再多就是概念分类，再少就会让 agent-server 依赖全部业务代码 | R1 R2 |
| D2 | **先合模块、再改包名**，两段式迁移 | 一次同时改 pom 与包名会产生数千行不可评审的 diff；分开后每段都能编译、测试、回滚 | §7 |
| D3 | 保留根命名空间 `io.seekflux` | 4 个 app 都是 `scanBasePackages = "io.seekflux"`，包移动不改变组件扫描范围，避免大范围改装配 | D4 R7 |
| D4 | 每个域固定三段 `api` / `domain` / `adapter`，跨域只允许引用 `api` | 用可判定的目录规则替代"六边形"口号；`api` 是唯一对外契约面 | R3 R4 R6 |
| D5 | 装配下沉：Bean 构造从 `apps/bootstrap` 移入各模块的 `@Configuration` | 否则 apps 仍要 import 30+ 个实现类型，R5 的"对外类型 ≤12"无法成立 | R5 |
| D6 | 不拆分 552 行的 `ElasticsearchSearchAdapter` | 它同时实现检索与 Feed 两个端口，但共享索引映射与查询构造；拆分是行为风险，收益只是审美。改为把 `RankingCandidate`/`RetrievalSource` 提升为 `recommendation.api` | R7 |
| D7 | 不引入 Spring Boot Starter / 自动配置 | `shared` 只有 2 个类，`agent` 只有一个消费方；Starter 是为第三方复用准备的，当前是内部单体 | R1 |
| D8 | 迁移按 8 个阶段推进，每阶段一个可独立回滚的提交 | 满足"任意中间态可编译、可测试"，让评审按阶段进行 | §7 R10 |

---

## 2. 目标模块与依赖图

### 2.1 坐标与目录

```text
seekflux/                                    (root, packaging=pom)
├── pom.xml                                  聚合 4 个 module 条目
├── core/pom.xml                             io.seekflux:seekflux-core
├── agent/pom.xml                            io.seekflux:seekflux-agent
├── shared/pom.xml                           io.seekflux:seekflux-shared
├── apps/
│   ├── pom.xml                              聚合 4 个进程
│   ├── online-server/pom.xml
│   ├── agent-server/pom.xml
│   ├── content-server/pom.xml
│   └── worker-runner/pom.xml
└── pipelines/realtime-features/pom.xml      直接挂在根下，不再要 pipelines/pom.xml
```

pom 数量：1（root）+ 3（库）+ 1（apps 聚合）+ 4（apps）+ 1（pipeline）= **10**，正好落在 R1 的 ≤10。
非聚合模块：3 + 4 + 1 = **8**。

### 2.2 模块依赖图（唯一允许的边）

```text
apps/online-server   ─┐
apps/content-server  ─┼─→ core ──→ (仅第三方)
apps/worker-runner   ─┤
apps/agent-server    ─┴─→ agent ──→ core
                     └─→ shared
```

| 允许 | 禁止 |
| --- | --- |
| `apps/* → core`、`apps/* → agent`、`apps/* → shared` | `core → agent`、`core → shared`、`shared → 任何业务包` |
| `agent → core` | `agent → shared`、`apps/a → apps/b` |

> `agent → core` 是必要边：`SearchDirectTool` 实现 Runtime 的 Tool SPI，内部调用 `core.search.api.SearchUseCase`。方向与"Agent 调用检索"一致，不构成环。

### 2.3 `core` 内部依赖

```text
content          ──→ (无)
feature          ──→ (无)          # interaction 已并入
search           ──→ feature.api   # 短期兴趣、内容热度加权
recommendation   ──→ feature.api   # 兴趣召回
adapter.elasticsearch ──→ search.api, recommendation.api   # 共享内容索引
```

跨域全部走 `api`，`domain` 不出域。

---

## 3. 目标包结构

### 3.1 `core`

```text
io.seekflux.core
├── content
│   ├── api/        ContentUseCase, SubmitContentCommand, CompleteContentProfileCommand, ContentView
│   ├── ContentApplicationService
│   ├── domain/     Content, ContentId, ContentProfile, ContentSource, ContentStatus,
│   │               ContentType, ContentStateException, ContentEvent
│   └── adapter/    JdbcContentRepository
├── search
│   ├── api/        SearchUseCase, SearchQuery, SearchResultPage, SearchHitView, SearchTrace,
│   │               SearchChannelTrace, SearchUnavailableException,
│   │               MultimodalSearchUseCase, MultimodalSearchQuery, MultimodalSearchResult
│   ├── SearchApplicationService, MultimodalSearchApplicationService
│   ├── domain/     SearchIndex, SearchDocument, SearchRetriever, SearchCandidate,
│   │               SearchRetrievalRequest, SearchRetrievalResult, SearchRetrievalSource,
│   │               Media*（Embedding / Segment / Understanding 端口与模型）
│   └── adapter/    （空：ES 适配器放在 core.adapter.elasticsearch）
├── feature
│   ├── api/        RealtimeFeatureUseCase, RealtimeFeatureProjectionUseCase,
│   │               FeatureRead, FeatureReadStatus, ContentHeatSnapshot,
│   │               ShortTermInterestSnapshot, FeatureTopicScore,
│   │               ReportInteractionsUseCase, ReportInteractionsCommand,
│   │               InteractionBatchReceipt, InteractionEventReceipt, InteractionIdempotencyConflictException
│   ├── RealtimeFeatureApplicationService, RealtimeFeatureProjectionService,
│   │   RealtimeFeaturePolicy, InteractionApplicationService
│   ├── domain/     FeatureKeys, FeatureProjectionDisposition, FeatureProjectionResult,
│   │               RealtimeFeatureEvent, InteractionSignal, InteractionType,
│   │               InteractionSurface, InteractionDisposition, InteractionBatch
│   └── adapter/    JdbcInteractionRepository, JdbcRealtimeFeatureProjector
├── recommendation
│   ├── api/        RecommendationUseCase, RankingUseCase, UserInterestUseCase,
│   │               RankingCandidate, RetrievalSource, RankedCandidate, RankingRequest,
│   │               RecommendationRetriever, UserInterestRepository
│   ├── RecommendationApplicationService, RuleRankingService, ExplicitInterestService
│   ├── domain/     InterestProfile
│   └── adapter/    （空：Feed 查询由 core.adapter.elasticsearch 提供）
└── adapter
    └── elasticsearch/   ElasticsearchSearchAdapter, ElasticsearchMediaSegmentAdapter,
                         SemanticVectorEncoder, HashingSemanticVectorEncoder
    └── http/            HttpMultimodalEmbeddingAdapter
```

包深度：`io.seekflux.core.adapter.elasticsearch` = 4 层，符合 R6。

### 3.2 `agent`

```text
io.seekflux.agent
├── api/            AgentSearchUseCase, AgentSearchCommand, AgentSearchResult, AgentSearchState,
│                   AgentTraceView, AgentExecutionMode, AgentRequestedMode, AgentModule（装配入口）
├── AgentSearchApplicationService
├── orchestration/  SearchGoal, QueryConstraintSet, ConstraintPatch, QueryModeRouter,
│   │               SearchIntentAnalyzer, SearchPlan, SearchToolPolicy, SearchClarificationPolicy,
│   │               ConstraintVersionConflictException, FallbackPolicy
│   └── adapter/    SearchDirectTool, SearchFilteredTool, DirectSearchExecutionAdapter,
│                   AgentSessionGoalAdapter, AgentRuntimeExecutionAdapter
├── runtime
│   ├── api/        Router, RouterResult, AgentRunRequest, AgentRunResult, AgentTerminalState
│   ├── spi/        LlmClient, PromptResolver, AgentSessionStore, AgentTool, AgentToolExecutor,
│   │               AgentRunRecorder, PushEventPublisher, ExecutionAuthorityStore,
│   │               CancellationSignalStore, AgentShadowRecorder, AgentPlanner,
│   │               ContextEngine, FeatureNode, ToolPolicy
│   ├── kernel/     AgentRuntime, SessionExecutor, AgentLoop, AgentCallGuard, DefaultRouter,
│   │               DefaultFeaturePipeline, BuiltInFeatureNodes, DefaultContextEngine,
│   │               AgentToolRegistry, DefaultAgentToolExecutor
│   ├── model/      AgentDefinition, AgentSession, AgentRunEvent, AgentDecision,
│   │               AgentToolInvocation, AgentToolObservation, RuntimeContext, CancellationToken
│   └── adapter/    Redis{ExecutionAuthorityStore,CancellationSignalStore,ShadowSettingsStore},
│                   RedisAgentSessionProjection, OpenAiCompatibleLlmClient,
│                   DeterministicSearchLlmClient, ShadowingLlmClient, MapPromptResolver,
│                   Micrometer*（4 个观测器）, JdbcAgentSessionStore, JdbcAgentRunRecorder,
│                   JdbcAgentShadowRecorder
└── config/         AgentRuntimeConfiguration（从 apps/agent-server 迁入）
```

包深度：`io.seekflux.agent.runtime.adapter` = 4 层。

隔离规则：`runtime/**` 不得 import `orchestration/**`、`api/**`；反向允许。

### 3.3 `shared`

```text
io.seekflux.shared
├── outbox/   KafkaOutboxRelay（原 platform/messaging）
└── jdbc/     FlywayConfiguration（原 PersistenceConfiguration）
```

### 3.4 `apps`

```text
io.seekflux.apps.<server>
├── <Server>Application            @SpringBootApplication(scanBasePackages="io.seekflux")
├── api/                           Controller + Request/Response + ExceptionHandler
└── config/                        仅保留进程专属装配：
                                  online-server: 线程池、MinIO?、多模态开关
                                  content-server: MediaStorageConfiguration
                                  worker-runner: WorkerConfiguration、Kafka 消费者
                                  agent-server: 空（全部下沉 agent.config）
```

---

## 4. 类型搬运映射

### 4.1 按目标模块计数

| 目标 | 来源 | 生产类 | 说明 |
| --- | --- | ---: | --- |
| `core.content` | `content-context` 16 + `persistence/content` 1 + `ContentConfiguration` 1 | 18 | 包名 `io.seekflux.content.*` → `io.seekflux.core.content.*` |
| `core.search` | `search-context` 30 + `retrieval` 4 + `model-serving` 1 + `SearchConfiguration` 1 | 36 | ES 适配器进 `core.adapter.elasticsearch` |
| `core.feature` | `feature-context` 17 + `interaction-context` 13 + `persistence/{interaction,feature}` 2 + 装配 3（online 的 Feature/Interaction、worker 的 Feature） | 35 | 两个域合并；两份 `FeatureConfiguration` 合并为一份 |
| `core.recommendation` | `recommendation-context` 8 + `ranking-context` 6 + `user-interest-context` 4 + `RecommendationConfiguration` 1 | 19 | 三个域合并 |
| `agent` | `agent-orchestration-context` 43 + `agent-runtime` 68 + `persistence/agent` 3 + 装配 2 | 116 | 两个模块合并 |
| `shared` | `platform/messaging` 1 + `persistence/PersistenceConfiguration` 1 | 2 | — |
| `apps` | 原 50 个生产类迁出 8 个 Configuration | 42 | 只留 Controller、进程专属装配（`MediaStorageConfiguration`、`WorkerConfiguration`）与 Application |
| `pipelines` | `realtime-features` 6 | 6 | 仅改依赖坐标 |
| **合计** | | **274** | 与现状一致，无新增/删除业务类 |

### 4.2 包名变更规则

| 现状 | 目标 | 规则 |
| --- | --- | --- |
| `io.seekflux.content.port.in` / `port.out` | `io.seekflux.core.content.api` | 用例接口、命令、视图、跨域 DTO 全部合并到 `api` |
| `io.seekflux.content.port.out`（仓储/索引） | `io.seekflux.core.content.domain` | 出站端口属于域内契约，不再单独成包 |
| `io.seekflux.content.application` | `io.seekflux.core.content`（域根） | 应用服务与域同包，去掉一层 |
| `io.seekflux.content.adapter.*` | `io.seekflux.core.content.adapter` | 原本是空目录，新建时直接落位 |
| `io.seekflux.platform.persistence.content` | `io.seekflux.core.content.adapter` | 适配器回到所属域 |
| `io.seekflux.platform.retrieval.elasticsearch` | `io.seekflux.core.adapter.elasticsearch` | 服务两个域，独立于域 |
| `io.seekflux.platform.agentruntime.*` | `io.seekflux.agent.runtime.*` | `domain/model` → `model`；`domain/service` → `kernel`；`application/api` → `api`；`application/spi` → `spi`；`application/command` → `api`；`infrastructure` → `adapter` |
| `io.seekflux.agent.{domain,application,port.*,infrastructure.*}` | `io.seekflux.agent.{orchestration,api,orchestration.adapter}` | 业务语义与运行机制分离保持不变 |

`application/spi` 的三层分包（`spi/business/{context,feature,planner,tool}` + `spi/capability/*`）压缩为一层 `spi`，`*model` 子包并入 `runtime.model`。这是 R5 里"包数从 40+15 降到 ≤15"的主要来源。

---

## 5. 边界规则（ArchUnit）

依赖：`com.tngtech.archunit:archunit-junit5:1.4.1`，test scope，加在 `core` 与 `agent` 两个模块（版本已在根 `pom.xml` 的 `archunit.version` 声明，本次首次真正引入）。

### 5.1 `core`

```java
@AnalyzeClasses(packages = "io.seekflux.core")
class CoreArchitectureTest {

    // R3：core 不得依赖 agent
    @ArchTest
    static final ArchRule core_不得依赖_agent =
        noClasses().should().dependOnClassesThat().resideInAPackage("io.seekflux.agent..");

    // R4：跨域只能引用 api
    @ArchTest
    static final ArchRule search_只能看见_feature_api =
        noClasses().that().resideInAPackage("io.seekflux.core.search..")
            .should().dependOnClassesThat().resideInAPackage("io.seekflux.core.feature.domain..");

    @ArchTest
    static final ArchRule recommendation_只能看见_feature_api =
        noClasses().that().resideInAPackage("io.seekflux.core.recommendation..")
            .should().dependOnClassesThat().resideInAPackage("io.seekflux.core.feature.domain..");

    // 域内不得依赖框架
    @ArchTest
    static final ArchRule domain_不依赖框架 =
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..", "org.elasticsearch..",
                "org.apache.kafka..", "com.fasterxml..", "redis.clients..");
}
```

### 5.2 `agent`

```java
@AnalyzeClasses(packages = "io.seekflux.agent")
class AgentArchitectureTest {

    // R5：内核不得反向依赖业务编排
    @ArchTest
    static final ArchRule runtime_不得依赖_orchestration =
        noClasses().that().resideInAPackage("io.seekflux.agent.runtime..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "io.seekflux.agent.orchestration..", "io.seekflux.agent.api..");

    // 内核不得拿到业务语义
    @ArchTest
    static final ArchRule runtime_不含检索业务类型 =
        noClasses().that().resideInAPackage("io.seekflux.agent.runtime..")
            .should().dependOnClassesThat().haveSimpleNameStartingWith("SearchGoal")
            .orShould().dependOnClassesThat().haveSimpleNameStartingWith("ConstraintPatch");

    // R3：agent 不得依赖 shared
    @ArchTest
    static final ArchRule agent_不得依赖_shared =
        noClasses().should().dependOnClassesThat().resideInAPackage("io.seekflux.shared..");
}
```

### 5.3 反例验证（G3 门禁的一部分）

在 `runtime` 任一类里临时加入 `import io.seekflux.agent.orchestration.SearchGoal;`，运行 `mvn -pl agent test -Dtest=AgentArchitectureTest` 必须失败；移除后恢复通过。该步骤在 Gate 3 的验收任务中固定执行一次并记录。

---

## 6. 装配下沉

### 6.1 现状问题

`apps/agent-server/bootstrap/AgentRuntimeConfiguration.java` 一个文件 import 了 30+ 个 runtime 实现类型；`online-server` 有 4 个 Configuration 类。apps 因此被迫看见模块内部实现，"apps 只负责装配"变成了"apps 承担全部构造知识"。

### 6.2 方案

| 迁移 | 从 | 到 |
| --- | --- | --- |
| Agent 运行时装配 | `apps/agent-server/bootstrap/AgentRuntimeConfiguration` | `agent/config/AgentRuntimeConfiguration` |
| Agent 搜索装配 | `apps/agent-server/bootstrap/AgentSearchConfiguration` | `agent/config/AgentSearchConfiguration` |
| 检索装配 | `apps/online-server/SearchConfiguration` | `core/search/SearchConfiguration`（含检索线程池 Bean） |
| Feed 装配 | `apps/online-server/RecommendationConfiguration` | `core/recommendation/RecommendationConfiguration` |
| 特征装配 | `apps/online-server/FeatureConfiguration`、`apps/worker-runner/FeatureConfiguration` | `core/feature/FeatureConfiguration` + 各自 profile 条件 |
| 交互装配 | `apps/online-server/InteractionConfiguration` | `core/feature/InteractionConfiguration` |
| 内容装配 | `apps/content-server/ContentConfiguration` | `core/content/ContentConfiguration` |
| 留在 apps | `MediaStorageConfiguration`、`WorkerConfiguration`、`Application`、全部 Controller | — |

因为扫描根是 `io.seekflux`，模块内的 `@Configuration` 会被自动拾取，apps 无需 `@Import`。若某个 Bean 只在特定进程需要，用 `@ConditionalOnProperty` 或 profile 控制，不引入 Starter。

### 6.3 验收口径（对应 R5）

```bash
grep -rho "import io\.seekflux\.agent\.[A-Za-z0-9_.]*" --include=*.java apps | sort -u | wc -l
```

目标 ≤12。主要剩下 `AgentSearchUseCase` 与 5～6 个 DTO。

---

## 7. 迁移阶段

每个阶段结束都必须满足：`mvn -o -B -DskipTests compile` 通过、`mvn -o -B test` 90 个测试全绿、涉及进程的启动冒烟通过。每阶段一个提交，可用 `git revert` 单独回滚。

| 阶段 | 范围 | 产出 | 验证 | 回滚 |
| --- | --- | --- | --- | --- |
| S0 基线固化 | 不改结构；记录 `mvn test` 与 4 进程启动基线日志 | 基线记录 | 编译+测试 | 无需 |
| S1 删除空壳 | `experiment-context`、`moderation-context`、`platform/observability`、`apps/training-runner`、18 个空 `adapter` 目录；`apps/web/examples/d1` | -3 模块 | 编译+测试 | revert |
| S2 `shared` | 新建 `shared`，搬 `KafkaOutboxRelay` 与 Flyway 配置；删 `platform/messaging` | +1/-1 模块 | 编译+测试+4 进程启动 | revert |
| S3a `core` 合模块 | 7 个 context 合并为 `core`，**包名保持不变**，apps 依赖改为 `core`；删 7 个 pom | +1/-7 模块 | 编译+测试+3 进程启动 | revert |
| S3b 适配器归位 | `platform/persistence` 6 类、`retrieval` 4 类、`model-serving` 1 类迁入 `core` 并改包名；删 3 个模块与 `platform/pom.xml` | -3 模块 | 编译+测试+3 进程启动 | revert |
| S4 `agent` | `agent-runtime` + `agent-orchestration` 合并为 `agent`，包名按 4.2 重写；3 个 Jdbc 适配器迁入；删 `agent-runtime` | +1/-2 模块 | 编译+测试+agent 启动 | revert |
| S5 包收敛 | 每域三段化 `api/domain/adapter`；`interaction`→`feature`；`ranking`+`user-interest`→`recommendation`；包深度 ≤4 | 包结构定稿 | 编译+测试 | revert |
| S6 装配下沉 + 架构测试 | 按 §6 迁 Configuration；加 `CoreArchitectureTest`、`AgentArchitectureTest`；执行反例验证 | G3 生效 | 编译+测试+4 进程启动+反例 | revert |
| S7 文档与收尾 | `module-boundaries.md`、`README.md` 架构章节同步；`docs/learning/` 追加落地记录；跑 G7/G8 | 全门禁 | G1–G8 | revert |

### 7.1 为什么 S3 要拆成 a/b

S3a 只动 `pom.xml` 与依赖声明，零 Java 改动，diff 极小但效果最大（21→11 模块）。S3b 要改 11 个类的包名与 import，属于机械但面广的改动。合在一起会让"模块合并是否安全"与"包名迁移是否安全"两个问题纠缠在一起。

### 7.2 为什么不先改包名再合模块

Maven 不允许两个模块声明同名包而不产生拆包问题；先合模块后，包名迁移在单一编译单元内完成，出错点只有一个。

---

## 8. 规格对应表

| 需求 | 方案落点 | 判定方式 |
| --- | --- | --- |
| R1 模块收敛 | §2.1：10 个 pom / 8 个非聚合模块 | `[pom-count]` |
| R2 空壳清零 | S1 删除 3 个空模块；§3 每个模块均有生产类 | `[empty-module]`、`[single-class-module]` |
| R3 依赖单向 | §2.2 允许边；§5 架构规则 | `CoreArchitectureTest`、`AgentArchitectureTest` |
| R4 跨域只引用 api | §3 每域 `api/`；§5.1 两条规则 | 架构测试 + grep |
| R5 Agent 收敛 | §3.2 包合并 + §4.2 重写规则 + §6 装配下沉 | `[agent-api-surface]` ≤12，包数 ≤15 |
| R6 分层收敛 | §3 三段式，深度 ≤4 | `[package-depth]` |
| R7 行为等价 | 不改业务逻辑；只改坐标、包名、装配位置；D6 不拆适配器 | G1/G2/G4/G5/G6 |
| R8 文档一致 | S7；只改 `module-boundaries.md` 与 README 架构章节 | R8 验收命令 |
| R9 未使用产物 | S1（training-runner、web 脚手架） | 目录与 `git ls-files` 计数 |
| R10 构建时长 | 模块从 21 降到 8，reactor 调度开销下降 | 3 次取中位数 |

---

## 9. 被否决的方案

| 方案 | 否决理由 |
| --- | --- |
| 单 `core` 含 agent | agent-server 会传递依赖 content/recommendation/ranking 全部代码，"独立故障边界"与"类路径隔离"两个卖点同时失效 |
| 保留 5 个域模块 | 各自 300～1,100 行，仍要维护 5 套 pom 与依赖声明；域间边界改由 ArchUnit 守护更廉价 |
| 拆出 `agent-runtime-starter` 自动配置模块 | `.github/workflows/agent-runtime-release.yml` 的草案指向 `platform/agent-runtime-spring-boot-autoconfigure`，但读完全部 75 个类型后确认：没有任何跨进程复用场景，Starter 只会把 1 个模块变成 2 个 |
| 拆分 552 行 ES 适配器 | 索引映射、查询构造、结果映射三段逻辑共享；拆开后要么复制 200 行，要么抽出中间层再加一个包 |
| 合并 `content-server` 与 `worker-runner` | Q5 已决议不合并；同步写 API 与异步消费的失败语义不同 |
| 用 Gradle / 单模块 + 包边界 | 迁移成本与收益不成比例；Maven reactor 已能满足"按进程构建" |

---

## 10. 风险与回滚

| 风险 | 触发点 | 处置 |
| --- | --- | --- |
| 组件扫描漏掉 Bean | S3b/S4 包移动后 | 每个阶段跑 4 进程启动冒烟；`ApplicationContext` 启动失败即回滚该阶段 |
| `@Configuration` 下沉后条件冲突 | S6 | 保留 `@ConditionalOnProperty`；冲突时该 Configuration 退回 apps，并在 DESIGN 追加记录 |
| 90 个测试因包名改动无法编译 | S3b/S4/S5 | 测试随类同步移动；断言不变，仅改 import |
| agent 与 core 出现循环依赖 | S4 | `agent → core` 单向；若发现 core 反向引用，说明该类型应下沉到 core 或上移到 shared |
| 迁移期长、分支漂移 | 全程 | 每阶段一个提交、随时可停；不并行开发其他功能 |
| G7/G8 需要完整本地栈 | S7 | 在 S7 前先跑一次基线 `*-after.json` 留档，确保环境本身可用 |

回滚粒度：单阶段 `git revert <stage-sha>`；不会出现"回滚到中途不可编译"的形态，因为每个提交自身可编译。

---

## 11. Gate 3 任务边界预告

只界定切分原则，具体任务在 TASKS-001 展开：

1. 一个任务 = 一个阶段内的一个可独立验证的动作（例如"删除 3 个空模块并修正聚合 pom"）。
2. 涉及包移动的任务，必须给出"移动清单 + 验证命令 + 预期测试数"。
3. 涉及装配的任务，必须给出进程启动冒烟命令。
4. 最后一个任务固定是"全门禁 G1–G8 验收 + 文档同步"。

---

## 12. 评审记录

| 轮次 | 日期 | 结论 | 修改要点 |
| --- | --- | --- | --- |
| 1 | 2026-10-20 | 待评审 | 初稿；已按 Gate 1 的 Q1–Q5 决议编写 |
