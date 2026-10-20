# SPEC-001 架构收敛：移除不必要的模块、组件与分层

> **重做说明（2026-10-20）**：本文件写于基线 `9292ea8`。该基点落后 `origin/main@86593ba` **15 个提交**（含 Agent Runtime RC1/RC2 发布、`platform/agent-runtime-spring-boot-autoconfigure` starter 模块、MCP/HITL/Checkpoint 等特性，agent-runtime 从 68 个类增至 160 个）。重做分支为 `refactor/arch-slimming-v2`（基点 `86593ba`）。
>
> 因此本文件的 D7（"不引入 Starter"）与"8 个模块"目标**已失效**，需按新事实修订——runtime 与 starter 是真实的对外发布边界，应保留为独立模块。修订完成前，本文档只作为问题度量与需求依据，不作为目标形态依据。

| 项 | 内容 |
| --- | --- |
| 规格编号 | SPEC-001 |
| 状态 | 已通过（Gate 1）／已实施（T01–T09，2026-10-20） |
| 分支 | `refactor/arch-slimming`（基线 `9292ea8`，可编译、90 测试全绿） |
| 基线提交 | `9292ea8`（runtime 分层二次调整） |
| 撰写日期 | 2026-10-20 |
| 后续产物 | `DESIGN-001-arch-slimming.md`（Gate 2）、`TASKS-001-arch-slimming.md`（Gate 3） |
| 流程约定 | 见 [SDD 流程](README.md) |

---

## 1. 一句话目标

在**不改变任何外部行为**（HTTP 契约、事件契约、数据库结构、检索/Agent 效果）的前提下，把当前 21 个 Maven 模块（含 3 个空模块）、25 个单类包、最深 7 层的包结构，收敛为"3 个库模块 + 4 个进程"，并把架构边界从"目录数量"转移到"可执行的架构测试"上。

## 2. 现状度量

以下数字均可由第 6.4 节的命令复现。

### 2.1 代码规模

| 区域 | 主代码文件 | 主代码行 | 测试文件 | 测试行 |
| --- | ---: | ---: | ---: | ---: |
| `contexts/`（10 模块） | 129 | 5,212 | 15 | 1,520 |
| `platform/`（6 模块） | 81 | 5,650 | 9 | 1,168 |
| `apps/`（4 进程） | 47 | 2,568 | 9 | 547 |
| `pipelines/`（1 模块） | 6 | 395 | 2 | 71 |
| **合计** | **263** | **13,825** | **35** | **3,306** |

> 数字取自基线提交 `9292ea8` 的受控文件。初次统计在含未提交 WIP 的工作区进行，多算了 11 个文件（274/14,756），已修正，见 TASKS-001 的 E1。

90 个测试方法，`mvn -B test` 全绿（基线提交 `9292ea8`）。

### 2.2 模块清单与真实角色

| 模块 | 主代码 | 实际职责 | 判定 |
| --- | ---: | --- | --- |
| `contexts/agent-orchestration-context` | 35 / 1,490 | Agent 业务编排 | 合并 |
| `contexts/content-context` | 16 / 890 | 内容写侧 | 保留 |
| `contexts/search-context` | 30 / 1,111 | 检索用例 + 全部端口，**无 domain、无 adapter** | 保留 |
| `contexts/recommendation-context` | 8 / 514 | Feed 用例，**只有端口** | 合并 |
| `contexts/user-interest-context` | 4 / 106 | 显式兴趣 | 合并 |
| `contexts/interaction-context` | 13 / 279 | 行为入站 | 合并 |
| `contexts/feature-context` | 17 / 490 | 实时特征 | 合并 |
| `contexts/ranking-context` | 6 / 332 | 规则排序 | 合并 |
| `contexts/experiment-context` | **0** | 空壳 | 删除 |
| `contexts/moderation-context` | **0** | 空壳 | 删除 |
| `platform/agent-runtime` | 68 / 2,999 | Agent 运行时内核 | 合并 |
| `platform/persistence` | 7 / 1,534 | **各业务域的 JDBC 适配器** | 拆回业务域 |
| `platform/retrieval` | 4 / 856 | **search/recommendation 的 ES 适配器** | 拆回 search |
| `platform/messaging` | **1** / 162 | Outbox Relay | 移入 shared |
| `platform/model-serving` | **1** / 99 | 多模态 HTTP 适配器 | 移入 search |
| `platform/observability` | **0** | 空壳 | 删除 |
| `apps/online-server` | 19 / 789 | 进程 | 保留 |
| `apps/agent-server` | 8 / 808 | 进程 | 保留 |
| `apps/content-server` | 10 / 360 | 进程 | 保留（见 Q5） |
| `apps/worker-runner` | 10 / 611 | 进程 | 保留 |
| `pipelines/realtime-features` | 6 / 395 | Flink Job | 保留（见 Q4） |
| `apps/training-runner` | **0**（仅 `pyproject.toml` + 2 个 `.gitkeep`） | 未注册进 `apps/pom.xml`，构建不参与 | 删除 |

模块平均承载 **658 行**主代码。最小的两个库模块各只有 **1 个类**。

### 2.3 包的粒度与深度

| 指标 | 当前值 |
| --- | --- |
| `platform/agent-runtime` 包数 | 40 个包 / 68 个类，平均 **1.7 类/包** |
| 其中只含 1 个类的包 | **25 个**（62.5%） |
| 其中只含 ≤2 个类的包 | 33 个 |
| 全仓包路径深度分布（相对 `io.seekflux`） | 2 层 72 个、3 层 126 个、4 层 20 个、**5 层 32 个、6 层 16 个、7 层 8 个** |
| 最深包 | `io.seekflux.platform.agentruntime.application.spi.business.planner.model`（7 层） |
| `platform/` 中只含 1 个类的包 | **38 / 57** |
| `contexts/` 中只含 1 个类的包 | **25 / 54** |

### 2.4 文档规模

| 文件 | 行数 | 性质 |
| --- | ---: | --- |
| `Ark-Leto 框架内核 与 Agentspark 主链路 原理详解.md`（仓库根目录） | 2,384 | 外部框架的阅读笔记，与代码无引用关系 |
| `SeekFlux.md` | 2,288 | 目标架构 + 已实现现状 + 未来路线混编 |
| `docs/learning/`（12 篇 Step + README + 模板） | 1,785 | 交付历史 |
| `docs/adr/`（10 篇） | — | 长期决策 |

文档总行数 7,483，约为主代码行数的 54%。

---

## 3. 问题陈述

每条问题给出来源证据，编号用于后续方案与任务的追溯。

**P1 空壳模块占用编译单元。** `contexts/experiment-context`、`contexts/moderation-context`、`platform/observability` 只有 `pom.xml` 与 `.gitkeep`，0 个类。它们参与 `mvn` reactor、进入依赖声明、增加 IDE 索引，但不提供任何能力。证据：第 6.4 节 `empty-module` 命令。

**P2 一个类一个 Maven 模块。** `platform/messaging`（1 类 162 行）、`platform/model-serving`（1 类 99 行）各自是一个独立模块。模块边界应表达团队/发布/资源边界，而不是"这个类看起来像基础设施"。

**P3 `platform` 语义被污染，并出现依赖倒置。** `platform` 同时装两类东西：横向技术（`agent-runtime`）与**业务域的出站适配器**（`persistence`、`retrieval`、`model-serving`）。后者反向依赖业务模块：

```text
platform/persistence   -> content-context, feature-context, interaction-context
platform/retrieval     -> search-context, recommendation-context, ranking-context
platform/model-serving -> search-context
```

`docs/module-boundaries.md` 同时写着 `platform -> context output ports（实现阶段按需依赖）`，等于把倒置写成了规则。结果是"业务域"不拥有自己的持久化和检索实现，适配器集中在一个名叫 platform 的目录里。

**P4 除 Agent 外，9 个业务 Context 没有一个拥有自己的适配器。** 这 9 个 context 都建了 `adapter/in` 与 `adapter/out` 目录，但**18 个目录里只有 `.gitkeep`**；`agent-orchestration-context` 用的是另一套命名 `infrastructure/`，也是唯一真正放着适配器实现的 context。`search-context` 的 30 个类全部是 application service、端口接口与 DTO，ES 实现位于 `platform/retrieval`，JDBC 实现位于 `platform/persistence`。所谓六边形边界，实际形态是"业务域只留接口、实现全部外置"，这既不是六边形也不是分层，而是把一次依赖注入拆成了五个模块。

**P5 只有一个消费方的"可复用框架"。** `platform/agent-runtime` 68 个类、2,999 行、75 个 public 类型、40 个包，对外的 API 只有两个方法：

```java
Router.execute(FeatureRequest, PushEventPublisher)
Router.cancel(String sessionId, boolean steer)
```

它的消费者只有 `agent-orchestration-context`（22 个文件、41 条 import）、`agent-server`（12 个文件、60 条 import，装配用）与 `platform/persistence`（3 个 SPI 实现、15 条 import）。"可复用"目前是假设，不是事实；而为了支撑这个假设付出的成本是 SPI 三层分包（`spi/business/*`、`spi/capability/*` 及其 `model` 子包）与平均 1.7 类/包的碎片化。

**P6 跨域直连领域对象。** `SearchApplicationService` 直接 `import io.seekflux.feature.domain.ContentHeatSnapshot / FeatureRead / ShortTermInterestSnapshot`，绕过 `docs/module-boundaries.md` 自己写的"跨上下文只允许输入 Port、版本化 DTO 或领域事件"。当前没有任何编译期或测试期规则能发现这类越界。

**P7 幽灵产物。** `apps/training-runner` 未注册进 `apps/pom.xml`，只有 3 个占位文件；`apps/web` 的 28 个受控文件里，`examples/d1/`、`db/`、`drizzle/`、`worker/`、`build/local-cloudflare-workers.ts`、`.openai/hosting.json` 属于托管平台脚手架，与实际业务（`app/page.tsx`、`app/SeekFluxApp.tsx`、`app/api/bridge/`）无关。

**P8 未提交的 WIP 曾让工作区不可编译（已隔离）。** 原先有 16 项未提交改动（全部为新增），扫描后确认**引用了 29 个不存在的类型**，涉及 capability / wait / context / message / mcp / autoconfigure 六组，例如 `CapabilityRequest`、`CapabilityResolver`、`WaitState`、`WaitResolution`、`WaitTimeoutProcessor`、`ContextEventRecorder`、`PushEventRelay`、`PushFrame`、`McpEventRecorder`、`CancellationCause`；此外 `RedisCancellationSignalStore.write(String, CancellationCause, Instant)` 与 SPI 的 `write(String, boolean, Instant)` 签名不一致。这批改动已按 Q1 的决议原样提交到独立分支 `wip/agent-wait-capability`（`c4e3f0c`），本分支回到 `9292ea8` 的绿色基线。

**P9 文档体量与代码体量倒挂，且事实分散。** 2,384 行外部框架笔记放在仓库根目录，读者无法区分"项目事实"与"参考资料"；`SeekFlux.md` 把目标架构、已完成现状、后续路线写在同一个 2,288 行文件里，与 `docs/learning/README.md` 的"唯一进度入口"职责冲突。

> 说明：`archunit.version` 已在根 `pom.xml` 声明，但**从未作为依赖引入，也没有任何架构测试**。也就是说，当前唯一的边界保障是 Maven 的模块依赖（编译期），包级与分层规则完全靠人自觉。削减模块数量必须同时补上这一层，否则是在降低约束而不是简化结构。

---

## 4. 目标与非目标

### 4.1 目标

1. **模块数量与真实边界对齐**：模块数等于"真实的发布/复用/隔离单元"，不再等于"概念分类"。
2. **一个能力一个位置**：接口、实现、适配器在同一模块内可见，删除纯转发的层。
3. **依赖单向且可验证**：不存在基础设施反向依赖业务域；跨域只能引用契约，不能引用领域对象。
4. **边界由测试守护**：新增架构测试，违反依赖规则时构建失败，而不是只在文档里写。
5. **文档与事实一致**：`docs/module-boundaries.md` 描述的目录必须真实存在。

### 4.2 目标形态（建议，最终以 DESIGN-001 为准）

```text
seekflux/
├── core/                          # 业务域单模块（唯一编译单元）
│   └── io.seekflux.core
│       ├── content/               # 内容写侧 + JdbcContentRepository
│       ├── search/                # 检索用例 + ES 适配器 + 多模态检索
│       ├── feature/               # 交互事实 + 实时特征
│       └── recommendation/        # 兴趣 + 召回 + 规则排序
├── agent/                         # 智能体：runtime 内核 + 业务编排
│   └── io.seekflux.agent
│       ├── runtime/               # 有限步循环、Session、Tool、事件
│       └── orchestration/         # SearchGoal、ConstraintPatch、追问、回退
├── shared/                        # 纯技术：Outbox Relay、JDBC/JSON/指标
├── apps/                          # 4 个可部署进程（装配层，薄）
│   ├── online-server/
│   ├── agent-server/
│   ├── content-server/
│   └── worker-runner/
└── pipelines/realtime-features/   # Flink Job
```

目标：**3 个库模块 + 4 个进程 + 1 条流水线 = 8 个模块**，包路径深度 ≤ 4。

### 4.3 非目标

以下明确**不在**本次范围，避免范围蔓延：

- 不重写任何业务逻辑、算法、排序公式、Agent 决策流程；
- 不改变 HTTP 路径、请求/响应字段、状态码、错误码；
- 不改变 Kafka topic 名、事件 schema、数据库表结构与索引；
- 不改变检索与 Agent 的效果指标（不优化，也不允许回退）；
- 不引入新框架、新中间件、新语言；
- 不重构前端交互与视觉，只删除未使用的脚手架；
- 不实现 `experiment` / `moderation` 的占位能力（删除，不是补实现）；
- 不拆分微服务，也不做服务网格/多租户。

---

## 5. 需求

编号规则：`R<序号>`；验收标准必须是命令或测试可判定的。

### R1 模块收敛

| 项 | 内容 |
| --- | --- |
| 需求 | 非聚合 Maven 模块从 21 个降到 ≤8 个；聚合 pom 从 4 个降到 ≤2 个 |
| 验收 | `find . -name pom.xml -not -path "*/target/*" \| wc -l` ≤ 10 |
| 当前 | 26 个 pom（21 模块 + 4 聚合 + 1 根） |

### R2 空壳清零

| 项 | 内容 |
| --- | --- |
| 需求 | 不存在 0 个生产类的**非聚合**模块；不存在只有 1 个类的库模块；不存在仅含 `.gitkeep` 的 `src` 目录 |
| 验收 | 6.4 节 `empty-module` 与 `single-class-module` 两条命令输出为空 |
| 当前 | 3 个空模块、2 个单类模块 |

### R3 依赖方向单向

| 项 | 内容 |
| --- | --- |
| 需求 | 目标依赖图：`apps/* → {core, agent, shared}`、`agent → core`、`core` 内部 `{search, recommendation} → feature.api`、`content` 独立；**禁止** `core → agent`、`core → shared`、`agent → shared`、`shared → 任何业务包`、`apps/a → apps/b`（`agent → core` 是 `SearchDirectTool` 调用检索契约的必要边，见 DESIGN-001 §2.2） |
| 验收 | 新增 `ArchitectureTest`，上述每条禁止项对应一条规则，人为引入一条违规时测试必须失败（附加一个反例验证用例） |
| 当前 | `platform/persistence`、`platform/retrieval`、`platform/model-serving` 反向依赖 6 个业务模块；无任何架构测试 |

### R4 跨域只引用契约

| 项 | 内容 |
| --- | --- |
| 需求 | 跨包域引用只允许 `*.api` 或 `*.dto` 包中的类型；不允许 `import ...<other>.domain.*` |
| 验收 | `ArchitectureTest` 规则 + `grep -rn "import io.seekflux.core.feature.domain" core/src/main/java/io/seekflux/core/search` 输出为空 |
| 当前 | `SearchApplicationService` 直接引用 `feature.domain` 的 3 个类型 |

### R5 Agent 内核收敛

| 项 | 内容 |
| --- | --- |
| 需求 | `agent-runtime` 与 `agent-orchestration` 合并为一个 `agent` 模块；`runtime` 与 `orchestration` 保留包级隔离，`runtime` 不得 import `orchestration`；模块被 `apps` 直接引用的类型 ≤12 个；包数量从 40+15 降到 ≤15，包路径深度 ≤4 |
| 验收 | `ArchitectureTest` 隔离规则 + 6.4 节 `agent-api-surface` 命令输出 ≤12 |
| 当前 | 75 个 public 类型，40 个包（25 个单类包），外部 import 95 处，对外 API 2 个方法 |

### R6 分层收敛

| 项 | 内容 |
| --- | --- |
| 需求 | 删除纯转发结构：不再要求 `port/in`、`port/out`、`domain`、`application`、`adapter` 五段式；一个用例的接口、实现与适配器在同一包内可达；包路径深度 ≤4 层 |
| 验收 | 6.4 节 `package-depth` 输出 ≤4；每个业务包内 `*UseCase`、`*Service`、`*Adapter` 可共处一包（抽查 3 个包） |
| 当前 | 5～7 层 56 个文件；`search-context` 30 个文件中 28 个是接口与 DTO |

### R7 行为等价

| 项 | 内容 |
| --- | --- |
| 需求 | 契约文件零 diff；测试数量不下降且全绿；4 个进程可独立启动；OpenAPI 声明的 15 个路径全部仍可用 |
| 验收 | `git diff --stat 9292ea8 -- contracts/ deploy/compose/postgres/init.sql` 为空；`mvn -B test` 输出 `Tests run: ≥90, Failures: 0, Errors: 0`；`./seekflux.sh up && ./seekflux.sh status` 四进程 healthy；15 个路径逐个 `curl` 返回非 404 |
| 当前 | 90 个测试全绿（基线提交） |

### R8 文档与代码一致（不裁剪既有文档）

| 项 | 内容 |
| --- | --- |
| 需求 | (a) 结构变更后，`docs/module-boundaries.md` 与 `README.md` 的"架构概览"章节必须同步为真实目录与依赖，不允许描述不存在的路径；(b) 本次变更**不移动、不删减、不合并、不重写**任何既有文档：`SeekFlux.md`、`docs/adr/`、`docs/learning/`、根目录 Ark-Leto 笔记均保持原位与原篇幅；(c) 新增内容只写入 `docs/sdd/` 与本变更的落地记录 |
| 验收 | (a) 抽查 `module-boundaries.md` 中出现的 10 条路径全部 `test -d` 通过；(b) `git diff --stat 9292ea8 -- SeekFlux.md docs/adr docs/learning "Ark-Leto 框架内核 与 Agentspark 主链路 原理详解.md"` 为空；(c) `docs/sdd/` 下有 DESIGN/TASKS 两份产物 |
| 当前 | 文档声明 `contexts/<ctx>/adapter/out`，但 9 个 context 下共 18 个 `adapter` 目录只有 `.gitkeep` |

### R9 未使用产物清理

| 项 | 内容 |
| --- | --- |
| 需求 | 删除 `apps/training-runner`；`apps/web` 只删除确认不可达的模板示例 `examples/d1/`。**保留** `vite.config.ts`、`build/`、`.openai/hosting.json`、`worker/`——`vite.config.ts` 直接 import 了前两者，`worker/index.ts` 是 vinext 构建入口，删除会破坏构建。`db/`、`drizzle/`、`drizzle.config.ts` 为未接入的模板数据层，本次不动，在 DESIGN 中标注为"待定" |
| 验收 | 目录不存在；`git ls-files apps/web \| wc -l` = 26（28 − 2）；`npm run build` 在 T10 验证 |
| 当前 | 训练模块 3 个占位文件；前端 28 个文件（其中 ≥6 个为脚手架） |

### R10 构建与验证时长不劣化

| 项 | 内容 |
| --- | --- |
| 需求 | 全量 `mvn -B test` 墙钟时间不高于基线的 110% |
| 验收 | 同一机器、同一 JDK 下各测 3 次取中位数 |
| 当前 | 基线 135 s 实测（首次运行含 surefire 插件下载），稳定后约 60 s |

---

## 6. 验收门禁

### 6.1 硬门禁（任一不满足即不通过）

| 门禁 | 命令 | 通过标准 |
| --- | --- | --- |
| G1 编译 | `mvn -B -DskipTests compile` | BUILD SUCCESS |
| G2 测试 | `mvn -B test` | `Tests run: ≥90, Failures: 0, Errors: 0` |
| G3 架构 | `mvn -B test -Dtest=ArchitectureTest` | 通过；且移除任一规则后能失败（反例验证） |
| G4 契约 | `git diff --stat 9292ea8 -- contracts/ deploy/compose/postgres/init.sql` | 空 |
| G5 进程 | `./seekflux.sh up` + `./seekflux.sh status` | 4 个 Java 进程 healthy |
| G6 冒烟 | `POST /v1/search`、`POST /v1/agent/search`、`POST /v1/contents`、`POST /v1/interactions:batch` | 200/202，字段与基线一致 |
| G7 检索效果 | `python evals/run_direct_search_eval.py --min-recall 1.0 --output evals/results/direct-search-v1-after.json` | 退出码 0；`recall@5 = 1.0`，不低于 `direct-search-v1-baseline.json` |
| G8 Agent 效果 | `python evals/run_complex_agent_eval.py --min-mrr-gain 0.5 --min-tool-selection-accuracy 1.0 --output evals/results/complex-search-v1-after.json` | 退出码 0；`agentAggregate.precision@1 = 1.0`，与 `complex-search-v1-baseline.json` 持平 |

> G7/G8 是硬门禁（Q4 决议）：需要本地栈（PostgreSQL/Redis/Kafka/ES/MinIO + 4 进程）就绪。重构过程中允许阶段性不跑，但**合并前必须跑通并留存 `*-after.json`**。基线值：direct `recall@5=1.0 / mrr@5=1.0`，complex agent `precision@1=1.0`。

### 6.2 软门禁（需人工确认，允许记录偏差）

- `apps/web` 构建产物可用，发现页三条主路径（发现/搜索/AI 搜索）可交互。
- 进程启动日志无新增 WARN/ERROR（与基线日志对比）。

### 6.3 评审门禁

- Gate 2 方案必须逐条回应 R1–R10；未覆盖的需求要写明理由。
- Gate 3 任务必须每个任务给出独立验证命令与回滚点。

### 6.4 度量命令（供复现）

```bash
# [pom-count] 模块与 pom 数量
find . -name pom.xml -not -path "*/target/*" | wc -l

# [empty-module] 空模块（0 个生产类，排除聚合 pom）
for p in $(find . -name pom.xml -not -path "*/target/*" -not -path "./pom.xml" \
           -not -path "./contexts/pom.xml" -not -path "./platform/pom.xml" \
           -not -path "./apps/pom.xml" -not -path "./pipelines/pom.xml"); do
  d=$(dirname "$p"); n=$(find "$d/src/main" -name '*.java' 2>/dev/null | wc -l)
  [ "$n" -eq 0 ] && echo "EMPTY: $d"
done

# [single-class-module] 单类模块（生产类恰好 1 个）
find . -name pom.xml -not -path '*/target/*' | while read -r p; do
  d=$(dirname "$p"); n=$(find "$d/src/main" -name '*.java' 2>/dev/null | wc -l)
  [ "$n" -eq 1 ] && echo "SINGLE: $d"
done

# [package-depth] 包深度（相对 io.seekflux）
find contexts platform apps pipelines -name "*.java" -path "*/src/main/*" \
  | sed 's|.*/src/main/java/io/seekflux/||' | awk -F/ '{print NF-1}' | sort -n | uniq -c

# [agent-api-surface] apps 直接引用的 agent 模块类型数（重构后）
grep -rho "import io\.seekflux\.agent\.[A-Za-z0-9_.]*" --include=*.java apps | sort -u | wc -l

# [single-file-package] 单文件包统计
python - <<'PY'
import os
for base in ('contexts', 'platform'):
    tot = single = 0
    for r, _, f in os.walk(base):
        if 'target' in r: continue
        n = [x for x in f if x.endswith('.java')]
        if not n: continue
        tot += 1; single += (len(n) == 1)
    print(f'{base}: {single}/{tot} 单文件包')
PY
```

---

## 7. 约束

1. **不提交 Git**：按 `AGENTS.md`，除用户明确要求外不执行 `git add/commit/push`。
2. **不改变外部契约**：`contracts/`、OpenAPI、事件 schema、数据库初始化脚本视为只读。
3. **不降低测试覆盖**：删除模块不得删除测试；测试可移动到新包路径，但断言不得放宽。
4. **不引入新依赖**：`ArchUnit` 已在根 `pom.xml` 声明版本但未引入，属于本变更允许新增的唯一依赖。
5. **保持中文注释与文档风格**：现有代码注释为中文，重构不得改为英文。
6. **Java 21 + Spring Boot 3.5 不变**；不引入虚拟线程、不引入 Reactive。
7. **服务间调用方式不变**：仍为进程内调用 + HTTP，不引入 RPC。
8. **每步可编译**：任务拆分必须保证任意一个任务完成后 `mvn compile` 通过，禁止"先删后建"导致中间态红灯。

---

## 8. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 合并模块后编译期边界消失，耦合重新长回来 | 高 | R3 的 ArchUnit 规则先落地，再合并；架构测试作为 G3 门禁 |
| 大规模包移动导致 diff 巨大，评审失效 | 中 | 按"域"分任务，每个任务单独 commit；纯移动与行为修改严格分离 |
| `platform/persistence` 拆回业务域时，Spring 装配失效 | 中 | 每个任务后跑 `mvn test` + 进程启动冒烟，不做批量替换 |
| 跨域 DTO 化改变序列化字段 | 中 | 只做类型搬家，字段名与顺序不动；用现有 Controller 测试守护响应 JSON |
| Agent 内核与业务合并后，`runtime` 反向拿到业务语义 | 高 | 包级隔离 + ArchUnit 单向规则；`runtime` 包不得出现 `SearchGoal`/`ConstraintPatch` |
| Flink 流水线在重构中失联 | 低 | `pipelines/realtime-features` 保持独立模块不动，仅改依赖坐标 |
| 文档再次与代码脱节 | 中 | R8 的路径抽查写进 G1 门禁；`module-boundaries.md` 由 DESIGN 阶段重写 |

---

## 9. 已决事项（Gate 1 决议，2026-10-20）

| 编号 | 问题 | 决议 | 落地口径 |
| --- | --- | --- | --- |
| Q1 | 未提交 WIP | **A：独立 WIP commit** | WIP 原样提交到独立分支 `wip/agent-wait-capability`（`c4e3f0c`）；`refactor/arch-slimming` 保持 `9292ea8` 的绿色基线。WIP 不进入本次重构，也不在本分支修复 |
| Q2 | 目标形态 | **B：三库模块** | `core` + `agent` + `shared` + 4 apps + 1 pipeline = 8 模块 |
| Q3 | 文档裁剪 | **文档不动** | 不移动、不删减、不合并既有文档；只做 R8(a) 的一致性同步 |
| Q4 | Eval 门禁 | **硬门禁** | 升级为 G7/G8，合并前必须跑通并留存 `*-after.json` |
| Q5 | content-server / worker-runner | **不合并** | 保持 4 个可部署进程 |

### 9.1 Q3 的执行口径（如与你的本意不符请指出）

"文档不动"按以下方式执行：

- `SeekFlux.md`、`docs/adr/`、`docs/learning/`、根目录 Ark-Leto 笔记：**零改动**，行数不变、位置不变。
- `docs/module-boundaries.md` 与 `README.md` 的"架构概览"章节：**必须改**，因为本次结构变更后它们会描述不存在的目录。这不是裁剪，是避免制造新的文档谎言；改动范围仅限"路径与依赖描述"，不新增章节。
- 本变更的过程与结论只写 `docs/sdd/`，落地记录追加到 `docs/learning/` 对应 Step 的末尾链接（不重写任何 Step 正文）。

若你的意思是连 `module-boundaries.md` 也冻结，请指出——那 R8(a) 需要降级为"已知偏差"，并在 `docs/sdd/` 里单独记录待修。

---

## 10. 影响面清单

| 类别 | 受影响对象 | 说明 |
| --- | --- | --- |
| 删除 | `contexts/experiment-context`、`contexts/moderation-context`、`platform/observability`、`apps/training-runner` | 0 生产类 |
| 删除 | 9 个 context 下共 18 个只含 `.gitkeep` 的 `adapter/in` 与 `adapter/out` 目录 | 空占位 |
| 删除 | `apps/web/{examples,db,drizzle,worker,.openai,build}` | 托管平台脚手架 |
| 合并 | `agent-runtime` + `agent-orchestration` → `agent` | 111 个文件 |
| 合并 | `interaction` + `feature`；`ranking` + `user-interest` + `recommendation` → `recommendation` | 48 个文件 |
| 迁移 | `platform/persistence` → 各业务域 `adapter`；`platform/retrieval`、`platform/model-serving` → `search` | 12 个文件，2,489 行 |
| 迁移 | `platform/messaging` → `shared` | 1 个文件 |
| 改写 | `pom.xml` × 26、`docs/module-boundaries.md`、`README.md` 架构章节 | — |
| 新增 | `ArchitectureTest`、`docs/sdd/*` | — |
| 不变 | `contracts/`、`deploy/compose/postgres/init.sql`、`evals/datasets/`、HTTP 路径与字段 | G4 门禁 |
| 不变 | `SeekFlux.md`、`docs/adr/`、`docs/learning/`、根目录 Ark-Leto 笔记 | R8(b)：零改动 |
| 隔离 | WIP 批次已移出本分支（`wip/agent-wait-capability`） | Q1 |

---

## 11. 评审记录

| 轮次 | 日期 | 结论 | 修改要点 |
| --- | --- | --- | --- |
| 1 | 2026-10-20 | 待评审 | 初稿 |
| 2 | 2026-10-20 | **通过（Gate 1）** | 按 Q1–Q5 决议更新：WIP 独立分支、目标形态 B、文档不动、Eval 升为硬门禁（G7/G8）、进程不合并；P8 依据实测改为"29 个缺失类型" |
