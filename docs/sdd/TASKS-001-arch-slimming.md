# TASKS-001 架构收敛任务拆分

| 项 | 内容 |
| --- | --- |
| 任务编号 | TASKS-001 |
| 状态 | 执行中 |
| 对应规格 | [SPEC-001](SPEC-001-arch-slimming.md)（Gate 1 已通过） |
| 对应方案 | [DESIGN-001](DESIGN-001-arch-slimming.md)（Gate 2 已通过） |
| 分支 | `refactor/arch-slimming`（基线 `9292ea8`） |
| 执行约定 | 每个任务一个提交；任务结束必须编译通过 + 90 个测试全绿；失败即停止，不叠加下一个任务 |

## 0. 通用验证命令

```bash
export JAVA_HOME="C:\\Users\\Hasee\\.jdks\\ms-21.0.12.1"
mvn -o -B -DskipTests compile          # G1
mvn -o -B test                         # G2：Tests run: 90, Failures: 0, Errors: 0
```

模块数、空模块、包深度等结构度量使用 SPEC-001 §6.4 的脚本。

## 1. 任务清单

### T01 删除空壳模块与不可达产物（S1）

| 项 | 内容 |
| --- | --- |
| 范围 | 删 `contexts/experiment-context`、`contexts/moderation-context`、`platform/observability`、`apps/training-runner`；从 `contexts/pom.xml`、`platform/pom.xml` 移除条目；删 9 个 context 下 18 个只含 `.gitkeep` 的 `adapter` 目录；删 `apps/web/examples/` |
| 不做 | 不动 `apps/web` 的 `vite.config.ts`、`build/`、`.openai/`、`worker/`、`db/`、`drizzle/`（前四项被构建直接引用） |
| 验证 | G1 + G2；`git ls-files apps/web \| wc -l` = 26 |
| 回滚 | `git revert` |
| 需求 | R2 R9 |

### T02 新建 `shared` 模块（S2）

| 项 | 内容 |
| --- | --- |
| 范围 | 新建 `shared/pom.xml`；`KafkaOutboxRelay` → `io.seekflux.shared.outbox`；`PersistenceConfiguration` → `io.seekflux.shared.jdbc.FlywayConfiguration`；4 个 app 依赖改为 `shared`；删 `platform/messaging` |
| 验证 | G1 + G2；`grep -r "platform.messaging" --include=*.java .` 为空 |
| 回滚 | `git revert` |
| 需求 | R1 R2 |

### T03 合并 7 个业务 context 为 `core`（S3a，包名不变）

| 项 | 内容 |
| --- | --- |
| 范围 | 新建 `core/pom.xml`；把 `content`、`search`、`feature`、`interaction`、`ranking`、`recommendation`、`user-interest` 七个包的 `src` 原样搬入 `core/src`（**package 声明一律不动**）；删除 7 个模块目录与 `contexts/pom.xml`；4 个 app 的依赖由 7 条改为 `core` |
| 关键约束 | 本任务**不产生任何 Java 内容变更**，diff 只应包含 pom 与文件移动 |
| 验证 | G1 + G2；`git diff -M --stat` 中 Java 文件显示为 rename |
| 回滚 | `git revert` |
| 需求 | R1 |

### T04 适配器归位到 `core`（S3b）

| 项 | 内容 |
| --- | --- |
| 范围 | `platform/persistence` 的 `JdbcContentRepository`、`JdbcInteractionRepository`、`JdbcRealtimeFeatureProjector` → `core` 的 `io.seekflux.<域>.adapter`；`platform/retrieval` 4 类 → `io.seekflux.core.adapter.elasticsearch`；`platform/model-serving` → `io.seekflux.core.adapter.http`；删 `retrieval`、`model-serving` 模块；`persistence` 暂留（还装着 3 个 agent 适配器） |
| 验证 | G1 + G2；`mvn -pl apps/online-server,apps/content-server,apps/worker-runner -am spring-boot:run` 冒烟由 T09 统一做，本任务先验编译与测试 |
| 回滚 | `git revert` |
| 需求 | R1 R3 |

### T05 合并 `agent-runtime` 与 `agent-orchestration`（S4a，包名不变）

| 项 | 内容 |
| --- | --- |
| 范围 | 新建 `agent/pom.xml`；`io.seekflux.agent.*` 与 `io.seekflux.platform.agentruntime.*` 两个包原样搬入 `agent/src`；删除两个源模块；`apps/agent-server` 依赖合并为 `agent` |
| 关键约束 | 不改任何 package 声明，只搬目录与 pom |
| 验证 | G1 + G2 |
| 回滚 | `git revert` |
| 需求 | R1 R5 |

### T06 agent 适配器归位（S4b）

| 项 | 内容 |
| --- | --- |
| 范围 | `platform/persistence/agent` 3 类 → `agent/src/.../io/seekflux/agent/adapter/jdbc`；删 `platform/persistence` 与 `platform/pom.xml`（此时 `platform` 目录清空） |
| 验证 | G1 + G2；`ls platform` 不存在 |
| 回滚 | `git revert` |
| 需求 | R1 R3 |

### T07 `core` 包收敛（S5a）

| 项 | 内容 |
| --- | --- |
| 范围 | 每个域整理为 `api` / `domain` / `adapter` 三段；`interaction` 并入 `feature`；`ranking` 与 `user-interest` 并入 `recommendation`；`port/in`→`api`、`port/out`→`domain`、`application`→域根；包深度 ≤4 |
| 验证 | G1 + G2；`[package-depth]` 最大值 ≤4 |
| 回滚 | `git revert` |
| 需求 | R4 R6 |

### T08 `agent` 包收敛（S5b）

| 项 | 内容 |
| --- | --- |
| 范围 | `domain.model`→`model`；`domain.service`→`kernel`；`application.api`/`command`→`api`；`spi/business/*` 与 `spi/capability/*` 压平为 `spi`；`infrastructure`→`adapter`；orchestration 的 `domain`→`orchestration`、`port.*`→`api`/`orchestration`、`infrastructure`→`orchestration.adapter` |
| 验证 | G1 + G2；`[agent-api-surface]` ≤12 |
| 回滚 | `git revert` |
| 需求 | R5 R6 |

### T09 装配下沉 + 架构测试（S6）

| 项 | 内容 |
| --- | --- |
| 范围 | 8 个 `@Configuration` 迁入 `core`/`agent`；引入 `archunit-junit5:1.4.1`（test）；新增 `CoreArchitectureTest`、`AgentArchitectureTest`；执行一次反例验证并记录 |
| 验证 | G1 + G2 + G3；4 进程启动冒烟 |
| 回滚 | `git revert` |
| 需求 | R3 R5 |

### T10 文档同步与全门禁（S7）

| 项 | 内容 |
| --- | --- |
| 范围 | 重写 `docs/module-boundaries.md`（真实目录与依赖）、`README.md` 架构章节；`docs/learning/README.md` 追加本变更记录；跑 G1–G8 |
| 验证 | G1–G8 全通过；`docs/sdd/` 三份文档状态更新为"已完成" |
| 回滚 | `git revert` |
| 需求 | R7 R8 |

## 2. 执行记录

| 任务 | 提交 | 状态 | 证据 |
| --- | --- | --- | --- |
| T01 | `8a8e8a7` | 已完成 | 删 3 空模块 + training-runner + 18 空目录 + examples；90 tests |
| T02 | `0da4584` | 已完成 | shared 建立，platform/messaging 删除；90 tests |
| T03 | `54bec57` | 已完成 | 7 context → core（零 Java 改动）；90 tests |
| T04 | `58209bf` | 已完成 | 9 个适配器归位，retrieval/model-serving 删除；90 tests |
| T05 | `562e818` | 已完成 | runtime + orchestration → agent（包名不变）；90 tests |
| T06 | `2dd3ee9` | 已完成 | platform/ 消失，pom 26 → 10；90 tests |
| T07 | `9311eec` | 已完成 | core 三段式 + 域合并；包深度 ≤4；90 tests |
| T08 | `06b396f` | 已完成 | agent 包数 40+ → 10，SPI 压平；90 tests |
| T09 | `068c140` | 已完成 | 装配下沉 + 8 条架构规则；98 tests |
| T10 | 本文档提交 | **部分完成** | 文档已同步；G5（4 进程启动）、G6（接口冒烟）、G7/G8（Eval）未执行 |

## 3. 执行偏差记录

| 编号 | 偏差 | 原因 | 处置 |
| --- | --- | --- | --- |
| E1 | 基线代码量由 274 文件 / 14,756 行修正为 **263 文件 / 13,825 行** | 初次统计在含未提交 WIP 的工作区进行，多算了 11 个文件 | SPEC §2.1 已按 `9292ea8` 重新统计 |
| E2 | R5 的"apps 引用 agent 类型 ≤12"实际为 **18** | 剩余 6 个是 admin 端点需要的 `ShadowControl`、`ShadowSettings` 与 3 个领域异常；彻底隔离需要额外的 admin facade，超出本次范围 | 记录为已知偏差，不强行凑指标 |
| E3 | S4 未按 DESIGN 在合模块时改包名，改为 T05 只搬目录、T08 统一改包 | 与 S3a/S3b 同一策略，避免一次改动同时承担"合并"与"重命名"两类风险 | DESIGN §7 的执行说明在本文档中更新 |
| E4 | 架构测试改用 `ClassFileImporter` + JUnit 断言 | `@ArchTest` 字段在 surefire 下未注册（Tests run: 0），规则会静默不生效 | 改为普通 `@Test`，8 条规则真实执行 |
| E5 | `apps/web` 只删除 `examples/d1` | `vite.config.ts` import 了 `build/` 与 `.openai/hosting.json`，`worker/index.ts` 是 vinext 入口 | SPEC R9 已修正，其余模板文件保留 |
| E6 | G7/G8 未执行 | 需要本地拉起 PostgreSQL/Redis/Kafka/ES/MinIO 与 4 个进程 | 合并前必须补跑，见 T10 剩余项 |
