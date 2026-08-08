# Ark-Leto 框架内核 与 Agentspark 主链路 原理详解

> 面向学习者的深度讲解文档。  
> Leto 源码基线：`1.2.2.1-RELEASE`，解压路径 `/tmp/leto-src/com/red/arkai/leto/`  
> （复现命令：`unzip -o ~/.m2/repository/com/red/arkai/leto/*/*/*-sources.jar -d /tmp/leto-src`）  
> Agentspark 源码即当前仓库。文中 `文件:行` 为撰写时的坐标，源码变动后以 `rg` 现查为准。

---

# 目录

- 第 0 章 怎么读这篇文档
- 第 1 章 全景：一次「点点」对话的完整生命周期
- 第 2 章 Ark-Leto 的分层哲学
- 第 3 章 Router：请求准入与幂等 commit
- 第 4 章 SessionExecutor：分布式单写者
- 第 5 章 Session：事件溯源
- 第 6 章 FeaturePipeline：可插拔的请求预处理
- 第 7 章 DefaultAgentLoop：ReAct 主循环
- 第 8 章 ContextEngine：上下文组装、压缩与渲染
- 第 9 章 LlmClient：模型访问抽象
- 第 10 章 Tool 子系统
- 第 11 章 PushEvent：流式事件模型
- 第 12 章 rendering/compat：SegmentType 与上游后处理协议
- 第 13 章 周边子系统：config/versioning、feature/、loop/chained
- 第 14 章 Agentspark 如何接入 Leto
- 第 15 章 Agentspark 的输出侧：Segment → FragmentDraft → BFF → 端
- 第 16 章 红线速查与排障索引

---

# 第 0 章 怎么读这篇文档

这篇文档回答三个层次的问题：

| 层次 | 问题 | 对应章节 |
|-|-|-|
| **是什么** | Leto 有哪些层？每层解决什么问题？ | 第 2 章 |
| **怎么跑** | 一条消息从 HTTP 进来到端侧渲染，中间经过了什么？ | 第 1、3–12 章 |
| **为什么这么设计** | 为什么执行权要先抢再 commit？为什么 IdIndex 节点必须 order=250？ | 全文的「为什么」小节 |

阅读建议：

1. 先看第 1 章建立全景直觉；
2. 再顺序读第 3–7 章（Router → SessionExecutor → Session → FeaturePipeline → AgentLoop），这是**主干**；
3. 第 8–12 章是主干上挂的四个大子系统（上下文、模型、工具、事件），可按需读；
4. 第 14–15 章是 Agentspark 侧的落地，把前面的抽象和实际业务代码对上；
5. 第 16 章当速查手册用。

一个贯穿全文的核心心智模型：

> **Leto 不是「一个 Agent 框架」，而是「一个把 Agent 执行做成可靠分布式服务的运行时」。**  
> 真正跑 LLM 的只有 `DefaultAgentLoop` 一个类；其余 80% 的代码都在解决：并发、幂等、插话、崩溃恢复、上下文超长、跨 pod 推送。

---

# 第 1 章 全景：一次「点点」对话的完整生命周期

## 1.1 一张图

```
【HTTP / 长连入口】
  DiandianController.chat()                       agentspark-start
    ↓ 鉴权 assertCanUseXhsUid
  DiandianMessageContextFactory                   HTTP/长连归一为 UserSendMessageContext
    ↓
  DiandianChatService.chat()                      agentspark-core（业务编排）
    ├─ resolveRacingFlags()                       一次 RPC 批量拉实验分桶
    ├─ RacingFlagDefaults.withWebFortuneDefault() Web 入口兜底
    ├─ 参数校验 + 输入安审
    └─ DiandianInvocationFactory                  构造 invocation（rawInputs 原样透传）
    ↓ port 调用（core 不 import leto）
  ArkDiandianAgentAdapter.stream()                agentspark-runtime（core→leto 边界）
    ↓ 虚拟线程
  DiandianArkRequestFactory                       构造 DiandianFeatureRequest
    ↓
╔══════════════════ Ark-Leto 内部 ══════════════════════════════════════╗
║  DefaultRouter.executeFeature()                                       ║
║    1) FeaturePipeline.process()   → 10 个 FeatureNode 串行             ║
║    2) FeatureContext → IncomingRequest                                ║
║    3) DispatchPrepareHookChain                                        ║
║    4) publisherRegistry.merge()   → 合并请求级 sink + session 订阅者    ║
║    5) commitIngressByAction()     → 抢执行权 + 幂等写入事件日志          ║
║    6) dispatchAfterCommit()       → busy/queued/duplicate 短路          ║
║    7) SessionExecutor.run()                                            ║
║         ├─ 注册 CancellationToken（本地 + Redis 远程信号）              ║
║         ├─ startAuthorityRenewal（心跳 10s / TTL 30s）                 ║
║         ├─ viewConsistentReread（强一致重读 Session）                   ║
║         ├─ DefaultAgentLoop.run()   ← ★ ReAct 主循环                   ║
║         │    for turn in [0, maxTurns):                                ║
║         │      ContextEngine.assemble()   ← 组装 + 压缩 + 渲染          ║
║         │      ToolCallHistory.detect()   ← 工具死循环检测              ║
║         │      LlmClient.stream()         ← 流式 + eager dispatch      ║
║         │      execute tools → session.append(ToolResultMessage)       ║
║         │      无 toolCall → OutputGuard → Completed                   ║
║         └─ drainQueuedMessages（排空插话消息并重跑）                     ║
╚═══════════════════════════════════════════════════════════════════════╝
    ↓ PushEvent 流
  DiandianArkEventMapper                          leto PushEvent → DiandianStreamEvent
    ↓
  ThinkingEventTransformer                        切分 <think>...</think>
  OutputAuditEventTransformer                     输出安审（fail-open）
    ↓
  ┌────────────────────┬──────────────────────────┐
  │ DiandianSseEventWriter │ LonglinkAgentEventBridge │
  │  (HTTP SSE)            │  (长连下行)               │
  └────────────────────┴──────────────────────────┘
    ↓
  FragmentDraftPayloadProjector                   draft → raw payload + content_type
    ↓
  ProtocolTranslatorChain                         agentspark-bff：raw → 端侧 element/spark
    ↓
  端（iOS / Android / RN / Web）
```

## 1.2 三条时间线

理解 Leto 的关键，是意识到同一个 session 上**同时存在三条时间线**：

| 时间线 | 谁在写 | 冲突点 |
|-|-|-|
| **执行线** | 当前持有执行权的那个 pod 上的 AgentLoop | 只能有一个（single-writer） |
| **插话线** | 用户在 loop 跑到一半时又发了一条消息 | 要打断执行线，但不能丢消息 |
| **恢复线** | HITL 审批回调、异步任务回调、崩溃后重启 | 要能从事件日志重建状态 |

Leto 的 Router + SessionExecutor + Session 三层，本质上就是在解决这三条线的**交汇问题**。第 3–5 章逐个拆开。

---

# 第 2 章 Ark-Leto 的分层哲学

## 2.1 六层结构

| 层 | 核心抽象 | 一句话职责 | 可替换粒度 |
|-|-|-|-|
| **Router** | `Router` / `IncomingRequest` | 请求准入、幂等 commit、并发门控 | Hook 链 |
| **SessionExecutor** | `ExecutionAuthority` / `CancellationToken` | 分布式单写者、cancel/steer、drain 重跑 | Store 实现（Jedis / 内存） |
| **FeaturePipeline** | `FeatureNode` | 可插拔的请求预处理插槽 | 全部节点 |
| **AgentLoop** | `AgentLoop` / `ExecutionOutcome` | ReAct 策略本身 | 整个 loop（按 `loopType` 多态） |
| **ContextEngine** | `ContextEngine` / `Compactor` / `ContextLayer` | 上下文组装、压缩、渲染 | Layer / Compactor / Renderer |
| **Session** | `WorkspaceEvent` / `SessionManager` | 事件溯源存储 | 存储后端（Redis / DB） |

## 2.2 一条不可改的顺序

```
RouterLayer  →  FeaturePipeline  →  AgentLoop
```

`FeaturePipeline.java:13-17` 的类注释明确写死了这个约束：**层间顺序不可改**，可改的只有 pipeline 内部的节点列表、顺序和场景路由。

**为什么**：如果允许在 AgentLoop 内部再回头跑 FeatureNode，那么「session 已加载 / RuntimeContext 已建 / 执行权已持有」这些前置不变量就不再成立，崩溃恢复和插话重跑的语义会全部失效。

## 2.3 「状态即事件序列」

Leto 里 Session 的状态**不是内存变量**，而是一条 append-only 的 `WorkspaceEvent` 日志（37 种事件）。所有的 `status()`、`messages()`、`queuedMessages()` 都是对这条日志的**投影**（projection）。

这个决定带来三个直接后果：

1. **崩溃恢复免费**：重启后从 Redis Stream 重放事件即可重建状态；
2. **插话重跑免费**：把 `QueuedUserMessage` 升格成 `UserMessage` 再跑一遍 loop 就行；
3. **调试友好**：整个会话的因果链就是那串事件。

代价是：任何「改状态」的操作都必须先设计成一个事件，不能直接改字段。

---

# 第 3 章 Router：请求准入与幂等 commit

源码：`router/DefaultRouter.java`、`router/Router.java`

## 3.1 Router 的三条入口

```java
// 旧路径：RouterPipeline（PipelineNode 链）
Result execute(Request request, PushEventPublisher sink, PostPipelineHook hook);
// 新路径：FeaturePipeline —— Agentspark 用这条
Result execute(FeatureRequest featureRequest, PushEventPublisher sink, PostPipelineHook hook);
// 直调 LLM（maxTurns=1，跳过完整 ReAct）
Result execute(DirectLlmRequest request);
```

外加两个控制面方法：`cancel(sessionId)` 软取消、`subscribe(sessionId, sink)` 长连订阅（生命周期独立于单次请求）。

`Result` 四态：`accepted`（异步已提交）、`queued`（排队/幂等去重）、`rejected`（门控拒绝）、以及同步执行时由 `ExecutionOutcome` 映射的结果。

## 3.2 executeFeature 的七个阶段

`DefaultRouter.java:300`：

| # | 阶段 | 做什么 |
|-|-|-|
| 1 | **FeaturePipeline** | `featurePipeline.process(req)` 跑完节点链，产出 `FeatureContext` |
| 2 | **适配** | `toIncomingRequest(featureContext)`：合成 `TriggerContext.UserMessageTrigger`，把 `persistentAttributes` 写进 `runtimeContext.features`（`transientAttributes`**不下传**） |
| 3 | **DispatchPrepareHook** | RuntimeContext 已建、AgentLoop 未跑的插桩点（评测 override、ephemeral skill 注入） |
| 4 | **publisher 合流** | `publisherRegistry.merge(sessionId, sink)`：本次请求 sink + session 已有长连订阅者 + 跨 pod fanout |
| 5 | **Ingress Commit** | `commitIngressByAction()` ← **核心**，见 3.3 |
| 6 | **派发** | `dispatchAfterCommit()`：按 commit 结果短路 |
| 7 | **执行** | 有 executor 则异步提交，否则同步 `sessionExecutor.run(...)` |

**关键认知**：Router 自己**不跑 LLM、不管并发安全的执行细节**。它只负责一件事——「把一条用户消息安全地 commit 进 session 事件日志，然后决定要不要起 loop」。

## 3.3 commitIngressByAction：13 个分支

`DefaultRouter.java:420`。这是**外部输入进入系统的唯一持久化入口**。按上游 `ResumeEvalFeatureNode` 算出的 `resumeAction` 分流：

### ① NEW_EXECUTION（`:427`）—— 主路径

三个子情形：

**子情形 A：终态窗口排队**（`:429`）

```java
if (session.status() == COMPLETED && !session.queuedMessages().isEmpty()) {
    yield commitTerminalWindowQueue(...);   // 写 QueuedUserMessage，无活 loop 则主动 drainPending
}
```

覆盖 cancel → drain 之间的缝隙。

**子情形 B：Position Guard（执行权门控）**（`:447`）

```java
if (sessionExecutor.usesDistributedAuthority()) {
    Optional<ExecutionAuthority> acquired = sessionExecutor.tryAcquireExecution(session.sessionId());
    if (acquired.isEmpty()) { yield new CommitResult(CommitOutcome.CONFLICT, incoming); }
    authority = acquired.get();
} else if (session.status() == EXECUTING) {
    yield new CommitResult(CommitOutcome.CONFLICT, incoming);
}
```

> **★ 这是整个 Router 里最重要的一行设计。**
> 
> 常规写法是「先 commit 消息，再判断能不能跑」。那样会出现 TOCTOU：A、B 两个请求都 commit 成功，都判断「当前没人在跑」，于是两个 loop 同时启动，对同一 session 双写。
> 
> Leto 的做法是把「判定能否执行」和「占住执行位」**原子地合并成一个 Redis `SET NX PX`**，并且放在 commit **之前**。抢不到 → 直接返回 `CONFLICT` → 调用方收到 `Result.busy`，消息压根不写。
> 
> commit 抛异常时，`catch` 块里立刻 `authority.close()`（`:464`）防止锁泄漏。

**子情形 C：正常提交**（`:460`）

```java
result = sessionManager.commitIngress(session.sessionId(), userMsg, trigger.requestId());
```

内部两步：① `trySetIdempotencyKey`（Redis SETNX，幂等键 = `requestId`）；② `eventStore.append(UserMessage)`。

`UserMessage` 带 `isRawContents=true` 标记——Router 只提交**原始**内容，模板渲染推迟到 `ContextEngine` 投影时执行。

结果映射：`Committed` → `PROCEED`（authority 随 `CommitResult` 传给后续 run）；`Duplicate` → 立刻 `authority.close()` + 返回 `DUPLICATE`。

### ② STEER_INTERRUPT（`:500` → `commitSteerInterrupt:569`）—— 插话

四步，**顺序不能反**：

```
1. enqueue()  → 写 QueuedUserMessage（携带 triggerMeta：插话者 userId）
2. sessionExecutor.cancel(sessionId, steer=true)
     → 本地 token 标 steer + 写 Redis 中断信号 "<ms>:steer"
3. publisher.publish(new PushEvent.MessageQueued(...))
4. sessionManager.evict(sessionId)   ← QUEUED_AND_DONE 不走 run 的 evict
5. 若 !isExecutionAlive(sessionId) → 僵尸兜底，主动 drainPending
```

> **为什么必须先写后 cancel？**  
> 正在跑的 loop 结束后会执行 `drainQueuedMessages`，它会做一次强一致重读。如果先 cancel 再写队列，loop 的 drain 重读可能抢在写入之前发生 → 读到空队列 → 直接退出 → 这条插话消息永远没人消费。

### ③ BLOCKED_BY_ASYNC（`:495`）

Session 正在 HITL / 异步任务 / waitpoint 等待。新消息只写 `QueuedUserMessage` + 发 `MessageQueued`，**不打断**当前等待。返回 `QUEUED_AND_DONE`。

### ④ CRASH_RECOVERY（`:502`）/ DIRECT_LLM_EXECUTION（`:507`）

都直接 `PROCEED`，不重复写 ingress。前者上游已检测到 `pendingToolCalls` 非空；后者消息已在 SessionLoad 阶段预填。

### ⑤ 八个恢复类分支（`:512-515`）

`RESUME_HITL` / `RESUME_ASYNC` / `TIMEOUT_ASYNC` / `CANCEL_ASYNC` / `RESUME_HANDOFF` / `WAIT_FOR_CHILD` / `RESUME_CHILD_AGENT` / `RESUME_WAITPOINT`。

这些 ingress 事件已由 `InternalIngressDispatcher` 在上游预先 commit，此处一律 `PROCEED` 不重复写。

> **设计价值**：不管是新消息、插话、HITL 审批回调、还是子 agent 完成，**全部收敛到同一条 commit + dispatch 路径**。新增一种恢复语义时，只需加一个 `resumeAction` 分支，不用碰执行、取消、推送的任何代码。

## 3.4 CommitOutcome 四态

| CommitOutcome | 含义 | 对外 Result |
|-|-|-|
| `PROCEED` | 正常进入执行 | `accepted` / 同步 outcome |
| `CONFLICT` | 执行权冲突 | `busy` |
| `DUPLICATE` | requestId 幂等去重 | `queued("duplicate requestId")` |
| `QUEUED_AND_DONE` | 已排队，无需执行 | `queued()` |

---

# 第 4 章 SessionExecutor：分布式单写者

源码：`router/SessionExecutor.java`

这一层解决的问题只有一个：**保证同一时刻，全集群只有一个 AgentLoop 在写这个 session**，并且在插话、失主、崩溃、停机时都不破这个不变量。

## 4.1 run() 的十五步

`SessionExecutor.java:183`：

```java
public ExecutionOutcome run(String sessionId, Session session, RuntimeContext ctx,
                            PushEventPublisher publisher, @Nullable ExecutionAuthority preAcquired) {
    if (closing) {                                    // ① 优雅停机门控
        closePreAcquiredQuietly(sessionId, preAcquired);
        return ExecutionOutcome.Cancelled.empty();
    }
    try {
        cancelTokens.put(sessionId, localToken);      // ② 注册进程内 token
        CancellationToken effectiveToken = buildCancellationToken(...);  // ③ 本地 + 远程组合
        if (preAcquired != null) heldAuthorities.put(sessionId, preAcquired);  // ④ 接管 Router 已抢的权
        else acquireAuthority(sessionId);
        fencedSessions.remove(sessionId);             // ⑤ 清上一区间残留失主标记
        renewalTask = startAuthorityRenewal(sessionId);  // ⑥ 心跳 10s / TTL 30s
        if (!validateAuthorityStillHeld(sessionId)) { // ⑦ 入口 owner-CAS
            return ExecutionOutcome.Cancelled.empty();
        }
        Session executing = viewConsistentReread(sessionId, session);  // ⑧ 强一致重读
        AgentLoop loop = resolveAgentLoop(ctx);       // ⑨ 按 loopType 查 registry
        ExecutionOutcome loopOutcome = loop.run(executing, ctx, publisher, effectiveToken);  // ⑩
        outcome = drainQueuedMessages(sessionId, executing, ctx, publisher, loopOutcome);    // ⑪
    } finally {
        stopAuthorityRenewal(renewalTask);            // ⑫ 停心跳
        cancelTokens.remove(sessionId);               // ⑬ 【放锁前】删 token
        releaseAuthority(sessionId);                  // ⑭ 释放执行权
        fencedSessions.remove(sessionId);
        if (!sessionManager.isLocalRestorage()) sessionManager.evict(sessionId);
    }
    drainResidualIfAny(sessionId, ctx, publisher);    // ⑮ 残留兜底
    return outcome;
}
```

## 4.2 五个非直觉的设计点

### ① 入口再校验一次执行权（⑦，`:219`）

```java
if (!validateAuthorityStillHeld(sessionId)) return ExecutionOutcome.Cancelled.empty();
```

内部调 `authority.renew(30000L)`——**这不只是续期，更是在验证锁还属于我**。

**为什么需要**：Router 里 acquire 发生在 commit 之前，到真正 run 之间可能隔着「异步排队 + 长 GC」。TTL 30s 可能已经过期并被别的 pod 接管。此处立即检测，失主就直接让位，绝不执行 AgentLoop。

### ② 视图收编（⑧，`:224`）

多节点模式下调 `sessionManager.restoreFresh(sessionId)`：失效本地缓存，从 Redis snapshot + 增量事件重建。

**为什么需要**：commitIngress 可能发生在**另一个 pod**（Router 层和执行层可以不在同一台机器）。不强一致重读，就看不到刚写进去的那条 `UserMessage`。

### ③ finally 的顺序：先删 token，后放锁（⑬⑭，`:231-233`）

**为什么**：如果先 `releaseAuthority` 再 `cancelTokens.remove`，那么在这两行之间，接管者可能已经抢到锁并 `cancelTokens.put(sessionId, 新token)`；紧接着本线程的 `remove(sessionId)` 会把**接管者的新 token 删掉**——典型 ABA。锁未放时别人进不来，此刻删 token 才安全。

### ④ Fencing 作用于整个持权区间（`:465`）

心跳续期失败 → 置 `fencedSessions` + cancel 当前 loop。`drainQueuedMessages` 的循环顶部会检查 `fencedSessions.contains(sessionId)`，命中就**整体停止 drain**。

**为什么不能只 cancel 当前轮**：drain 是个 `while(true)` 循环，每轮都会 `loop.run()`。如果 fencing 只作用于单轮，下一轮会用全新 token 重开，和已经接管的那个 pod 双写。

### ⑤ close() 必须先 cancel 后停 scheduler（`:339`）

```java
public void close() {
    closing = true;                                   // 新请求直接拒绝
    for (var token : cancelTokens.values()) token.cancel();  // 先让在途 loop 从检查点退出
    if (authorityScheduler != null) authorityScheduler.shutdownNow();  // 再停续期
}
```

**为什么**：反过来做（先停 scheduler），在途 loop 既收不到取消信号，又因为没有续期而丢锁，于是「无锁保护 + 仍在写」——正好是跨 pod 双写的最坏窗口。

## 4.3 drainQueuedMessages：插话重跑

`SessionExecutor.java:560`：

```
while (true) {
    if (fencedSessions.contains(sessionId)) return outcome;      // 失主 → 整体停
    Session fresh = sessionManager.restoreFresh(sessionId);      // 每轮强一致重读
    if (fresh == null || fresh.queuedMessages().isEmpty()) return outcome;

    List<QueuedMessage> batch = fresh.queuedMessages();
    rejectQueuedOverrides(batch);                                // 防御：拒绝携带 override 的排队消息
    RuntimeContext drainCtx = buildDrainContext(ctx, batch);     // 重建身份 + 清 per-request override
    for (QueuedMessage q : batch) fresh.append(UserMessage...);  // 全保真升格
    clearStaleInterruptSignal(sessionId, drainStartedAt);        // 只清旧信号，保留新取消
    cancelTokens.put(sessionId, drainLocalToken);
    if (fencedSessions.contains(sessionId)) return outcome;      // 二次 fencing 检查
    outcome = loop.run(fresh, drainCtx, publisher, effectiveDrainToken);
    snapshotQuietly(fresh, sessionId);
    session = fresh;
}
```

两个细节值得注意：

- **`clearStaleInterruptSignal`**（`:757`）：只清早于 `drainStartedAt` 的信号（即触发本次 drain 的那个 steer cancel），保留晚于本轮的新 cancel（用户刚点的真取消）。否则「插话后立刻取消」会被吞掉。
- **`buildDrainContext`**：取**最后一条**排队消息的 `metadata["userId"]` 重建身份（「最后意图者胜出」），合并 `renderDataPatch` / `ephemeralSystem` / `features`，并清除 `agentDefPatch` / `executionManifest` / `chainedAgentPatches` 这些 per-request override——防止一次评测请求的 override 泄漏到后续排队消息的重跑里。

## 4.4 两个 Store

### ExecutionAuthorityStore（`session/ExecutionAuthorityStore.java`）

```java
Optional<ExecutionAuthority> acquire(String sessionId, String ownerToken, long ttlMillis);
boolean isHeld(String sessionId);

interface ExecutionAuthority extends AutoCloseable {
    boolean renew(long ttlMillis);   // owner-CAS；失败 → fencing
    void close();                    // owner-CAS 释放；幂等
}
```

`JedisAuthorityStore` 实现要点：

- Key：`arkai:running:{sessionId}`，value = `ownerToken`（格式 `{hostname}:{pid}:{UUID}`）
- acquire = `SET key ownerToken NX PX 30000`
- renew：cluster/redis-direct 走 Lua 原子脚本（GET==owner 才 PEXPIRE）；**redkv 不支持 EVAL**，退化成非原子 GET + 条件 PEXPIRE，多 pod 强互斥降级为 best-effort（已在源码中文档化残窗）
- 异常一律返回 `false`（**宁可多接管，绝不漏判双写**）
- `isHeld` 异常时保守返回 `true`（避免误判为空导致双 loop）
- TTL 30_000ms，续期间隔 10_000ms（TTL 的 1/3）

**心跳与 turn 进度解耦**是关键：长 turn（比如一个 tool 跑 2 分钟）不会因为「没推进」而误判过期。

### InterruptSignalStore（`session/InterruptSignalStore.java`）

```java
CancelSignal poll(String sessionId, Instant taskStartedAt);  // 单次读，同时派生 cancelled + steer
void write(String sessionId, boolean steer);
void clear(String sessionId);
record CancelSignal(boolean cancelled, boolean steer) {}
```

`JedisInterruptSignalStore`：

- Key `arkai:interrupt:{sessionId}`，TTL 30s
- Value：`"<cancelMs>"`（普通取消）或 `"<cancelMs>:steer"`（插话）
- `cancelMs > taskStartedAt` 才生效——避免跨任务误消费上一轮的取消信号

> **为什么 `poll` 必须是单次读**：如果分两次读（先判 cancelled，再判 steer），中间 key 可能过期，导致「判出了取消但丢了 steer 标记」→ 插话语义降级成普通取消 → 前端拿到 `SegmentComplete` 而不是 `Steered`，同卡续段变成断开新起。

## 4.5 CancellationToken 三级

`agent/cancel/`：Session → Batch → Tool 三级层次，父 cancel 向下传播，子 cancel 不影响父。

- `DefaultCancellationToken`：`AtomicBoolean cancelled` + `volatile boolean steer`（单调，只升不降）
- `DistributedCancellationToken`：本地 token + 远程信号组合，远程结果缓存 `cacheTtlMs`（默认 2000ms）避免高频轮询 Redis；远程命中时调 `localToken.cancel(steer)` 把 steer 传播到本地，后续走快路径

---

# 第 5 章 Session：事件溯源

源码：`session/WorkspaceEvent.java`、`session/redis/AbstractRedisSession.java`、`AbstractRedisSessionManager.java`

## 5.1 WorkspaceEvent 全景（37 种）

`sealed interface WorkspaceEvent extends SessionEvent`，`category()` 恒为 `DOMAIN`。

**生命周期类**

| 事件 | 含义 |
|-|-|
| `SessionCreated` | 首条事件，携带 agentId、agentDefVersion、不可变 `SessionSpec`（ownerUserId/appId/ttl/maxTurns） |
| `SessionForked` | Fork 分支的可重放基线（把 fork 时刻的完整投影存为独立基线，而不是复制源事件流） |
| `SessionComplete` | 终态：正常完成 |
| `SessionCancelled` | 终态：取消或 STEER 打断，`reason` 为 `"STEER"` 或 null |
| `SessionFailed` | 终态：不可恢复错误，带 errorType |

**消息类**

| 事件 | 含义 |
|-|-|
| `UserMessage` | 用户输入。`contents: List<MessageContent>`、`isRawContents`（待渲染标记）、`displayContents`（UI 专用） |
| `QueuedUserMessage` | 插话/等待期到达的排队消息。额外带 `renderDataPatch`/`ephemeralSystem`/`features`/`metadata`（插话者身份），drain 时还原进 RuntimeContext |
| `UserMessageCancelled` | 取消特定消息 |
| `AssistantMessage` | LLM 回复。`text` / `toolCalls` / `reasoningContent`（**独立字段，不与正文拼接**）/ `reasoningContentReplayable` |
| `ToolResultMessage` | 一批工具结果。每个 `ToolResult` 含 `result`（文本视图）、`contents`（多模态视图）、`displayContents`（UI 视图）、`isError`、`structuredData`、`resources` |

**HITL / 异步 / 挂起类**

| 事件 | 含义 |
|-|-|
| `HitlPending` / `HitlDecision` / `HitlTimedOut` | 人工审批：发起 / 结论（APPROVED\|DENIED，带 checkpointId 供跨 pod 重驱动）/ 超时 |
| `AsyncTaskPending` / `Completed` / `Cancelled` / `TimedOut` | 异步任务四态 |
| `WaitpointSuspend` / `WaitpointResume` | 等待外部条件 |
| `HandoffSuspend` / `HandoffResume` | 移交子 Agent |
| `ChildAgentSuspend` / `ChildAgentResume` | as_tool 子 Agent |

**计划 / 步骤 / 技能类**

`StepAdvanced`、`TurnPhaseChanged`、`PlanCreated`、`PlanUpdated`、`PlanStepStarted`、`PlanStepCompleted`、`PlanSummaryRefreshed`、`SkillActivated`。

**基础设施类**

| 事件 | 含义 |
|-|-|
| `CompactionSummary` | 增量压缩产出。`compressedUntilPosition`（cutoff，inclusive）、`version`、`summary`、`tokenCount`、`role`、`metadata`（`@JsonAnyGetter/@JsonAnySetter` 扁平化兼容旧格式） |
| `AgentIdChanged` | 意图路由切换 agentId，last-wins 投影 |
| `ForkTurnPromoted` | Fork 子 session 的完整 turn 提升为主 session 权威投影。自包含（含消息序列 + promotedIdIndex），幂等键 operationId |

## 5.2 position：set-once 的因果序号

每个事件带 `long position`（初始 -1）。`SessionManager.append()` 调 `EventStore.append()` 拿到 seq 后立刻 `setPosition(seq)` 回填，**二次赋值抛 `IllegalStateException`**（`WorkspaceEvent.java:62`）。恢复路径从 `EventEnvelope.seq` 回填。

position 是后续压缩（cutoff 比较）、保护区（protectFirstN）、drain 幂等的物理基础。

## 5.3 append / snapshot / restore

**append**（`AbstractRedisSessionManager.java:800`）：

```
EventStore.append(sessionId, event)       → 分配 seq，写 Redis Stream
event.setPosition(seq)                    → set-once 回填
session.replayLocal(event, seq)           → 更新本地投影
triggerSnapshotIfNeeded(...)              → 按 SnapshotPolicy 触发（虚拟线程异步）
persistenceManager.onEvent(...)           → DB 持久化通知
```

`SnapshotPolicy` 三种触发：`saveOnSuspend`（挂起立即）、`saveOnComplete`（终态立即）、`saveIntervalEvents`（每 N 次 append）。

**restore 四条链路**（`:188`）：

```
链路1: 本地 sessionCache 命中 → 检查 isRemoteAhead(remotePos > cached.position()) → 领先则 evict，走链路2
链路2: SnapshotStore.load → snapshot + EventStore.loadAfter(snapshot.position) 增量重放
链路3: EventStore.loadAll → 全量重放（无快照）
链路4: PersistenceManager.restore → DB 冷恢复 + 回写 Redis snapshot
```

**replayLocal 的高水位原则**（`AbstractRedisSession.java:626`）：position 单调推进，`seq <= 0` 的合成事件**不回退**高水位。

## 5.4 status 投影表

`AbstractRedisSession.java:740`：

```
SessionCreated       → IDLE
UserMessage / AssistantMessage / ToolResultMessage → EXECUTING
HitlPending / AsyncTaskPending / WaitpointSuspend / HandoffSuspend → SUSPENDED
HitlDecision / AsyncTaskCompleted / WaitpointResume → EXECUTING
SessionComplete / SessionCancelled / SessionFailed → COMPLETED
```

`queuedMessages()` 投影用 `LinkedHashMap<messageId, QueuedMessage>`：`QueuedUserMessage` 入队，`UserMessage`（drain 升格）按 messageId 出队 —— **天然幂等**，重复 drain 不会重复消费。

## 5.5 历史如何进入 LLM

```
session.messages()                     ← 从事件日志投影出 User/Assistant/Tool 三类 Message
  → ContextEngine.convertSessionMessages()
  → 过滤 compressedUntilPosition 以下的已压缩消息
  → 注入 CompactionSummary 摘要消息（role 由 effectiveRole() 决定，默认 ASSISTANT）
  → MaskBlock.mask() 应用遮罩（Hide / Override / Pass）
  → UserPromptLayer 对 isRawContents=true 的 User 消息做模板渲染
  → 拼成 LLM API 的 messages 数组
```

---

# 第 6 章 FeaturePipeline：可插拔的请求预处理

源码：`router/feature/`

## 6.1 12 行的薄壳

`FeaturePipeline.java:34`：

```java
public FeatureContext process(FeatureRequest request) {
    FeatureContext context = new FeatureContext(request);
    List<FeatureNode> nodes = resolver.resolve(request);
    for (FeatureNode node : nodes) {
        node.process(context);
    }
    return context;
}
```

单线程串行，节点间靠 `FeatureContext` 传状态。整个「扩展性」都由 `FeatureNode` 和 `FeaturePipelineResolver` 两个接口撑起来。

```java
public interface FeatureNode {
    String name();
    int order();                       // 数值小先执行
    void process(FeatureContext context);
}
```

## 6.2 FeatureContext 的两类属性

`FeatureContext.java`：

| 类型 | 写入方法 | 去向 |
|-|-|-|
| **持久属性** | `setAttribute(k, v)` | 投影到 `RuntimeContext.features` → 进 checkpoint |
| **瞬态属性** | `setTransientAttribute(k, v)` | 仅 pipeline/hook 期间可见，**不进 checkpoint** |

框架字段：`session` / `agentDef` / `runtimeContext` / `resumeAction` / `cancelToken`。

## 6.3 框架内置的五个节点

| order | 节点 | 职责 |
|-|-|-|
| 50 | `FeatureStoreInitNode` | 把 userId/sessionId/agentId/sceneId/channel/metadata 注入 `FeatureStore` 计算上下文，以**瞬态属性**`featureStore` 写入 context |
| 100 | `SessionLoadFeatureNode` | `sessionManager.restore(sessionId)`。**不存在时不创建**（创建需要 AgentDef，时机在 200） |
| 200 | `AgentResolveFeatureNode` | 解析 agentId（req > session，都无则 400）→ `agentRouter.resolve()` 加载 AgentDef（404 抛异常）→ session/agent 一致性校验（不匹配 409）→ **session 不存在则 `sessionManager.create()`** → agentDefVersion 漂移打 WARN 不阻断 |
| 300 | `ParamInitFeatureNode` | `RuntimeContextFactory.create(...)` 构建 RuntimeContext。`LlmEndpointConfig` 优先取请求携带的，其次 `agentDef` 的。若 metadata 带 `checkpointId` 则从 CheckpointStore 恢复 |
| 400 | `ResumeEvalFeatureNode` | 算 `resumeAction`（见下） |

`ResumeEvalFeatureNode` 的决策：

```java
switch (session.status()) {
    case IDLE      -> NEW_EXECUTION;
    case EXECUTING -> isSteer(metadata) ? STEER_INTERRUPT : NEW_EXECUTION;
    case SUSPENDED -> evaluateSuspended(session);   // waitState 非空→BLOCKED_BY_ASYNC
                                                    // 无 waitState 但有 pendingToolCalls→CRASH_RECOVERY
                                                    // 否则→NEW_EXECUTION
    case COMPLETED -> NEW_EXECUTION;
}
```

注意：FeatureRequest 路径**始终是用户消息触发**。HITL/Async/Waitpoint 回调走 `InternalIngressDispatcher`，不经过这个节点。

## 6.4 推荐用 100 的倍数

框架节点占了 50/100/200/300/400。业务节点应插在中间的空档（Agentspark 用了 50/70/120/160/250/350），给未来预留插入空间。

---

# 第 7 章 DefaultAgentLoop：ReAct 主循环

源码：`agent/DefaultAgentLoop.java`（约 2600 行）、`agent/AbstractAgentLoop.java`

这是整个框架里**唯一真正跑 LLM 的类**。

## 7.1 入口与模板方法

```java
// AgentLoop.java:31
ExecutionOutcome run(Session session, RuntimeContext ctx,
                     PushEventPublisher publisher, CancellationToken cancelToken);
```

`AbstractAgentLoop.run()` 是 **final**（`:388`），统一做完这些后委托 `runInternal`：

1. `setCompletedTurnCount(ctx, 0)` —— 轮数计数器写进 `ctx.params()` 而非实例字段（**loop 实例是共享单例，必须无状态**）
2. 开 CAT Transaction（`"agent-loop"`）+ OTel Span
3. 发 `AgentEvent.LoopStarted`
4. 调 `runInternal(...)`
5. finally 发 `LoopCompleted`（turnCount / outcomeType / durationMs），异常发 `LoopFailed`

`DefaultAgentLoop.loopType()` 返回 `"default"`（`:656`）——`AgentLoopRegistry` 按这个字符串路由，所以「换一套 loop 策略」只需要注册一个新 loopType。

## 7.2 进入 for 之前的 13 步初始化

`runInternal()` 从 `:691` 开始：

| # | 动作 | 说明 |
|-|-|-|
| 1 | 生成 `segmentId` | 本次执行的段落 ID，贯穿所有 PushEvent |
| 2 | `snapshotSkillDefinitions()` | **冻结** SkillDef 快照（`AbstractAgentLoop.java:308`），防止执行途中热更导致指令漂移 |
| 3 | fail-fast：`chainedAgentPatches` 非空 → `Failed("UNSUPPORTED_OVERRIDE")`（`:701`） |  |
| 4 | `resolveEffectiveAgentDef()`（`:658`） | dimension overlay（`agentDefResolver.resolve`）→ per-request patch（`AgentDefOverlay.apply`） |
| 5 | `allowedTools = toolRefs - disallowedTools`（`:726`） |  |
| 6 | `initToolGroups()`（`:1521`） | `initialActiveGroups` → `ctx.params(ToolGroupParams.ACTIVE_GROUPS)` |
| 7 | 恢复 IdIndex 快照（`:736`） | `session.latestIdIndexSnapshot()` → `ctx.params()`，跨 dispatch 连续 |
| 8 | `bootstrapSessionState()` | HITL / Async / Child / Handoff 的恢复分支，见 7.9 |
| 9 | `skillRouter.resolveVisibleSkills()` + D5 shadow 过滤 + `autoActivate` |  |
| 10 | `composeToolDefs()` | Skill 解锁工具 ∪ base，再过 `ToolGroupFilter` + `ToolPermissionFilter` |
| 11 | `runPreflightStage()`（`:2319`） | 按 `order()` 并行执行 `PreflightToolCallProvider`（如 memory 检索） |
| 12 | 构建首轮 `ContextAssemblyRequest` | 含 ephemeralInjection / renderDataPatch / features / compactionPhase |
| 13 | `new ToolCallHistory()` | 滑动窗口死循环检测器 |

## 7.3 每一轮做什么（精确顺序）

`for (int turn = 0; turn < effectiveAgentDef.maxTurns(); turn++)`（`:839`，`maxTurns` 默认 20，`AgentDef.java:117`）

```
 1. cancelToken.isCancelled()          → Cancelled                      :840
 2. resolveTurnLlm()                   ← LlmTurnHookChain，null 则用 effectiveLlm  :858
 3. contextEngine.assemble()           ← 组装 + 压缩（第 8 章）           :863
 4. loopHistory.detect(K=3)            ← 死循环检测，见 7.6              :868
 5. fireCheckpoint(PRE_TURN)                                            :883
 6. publish(LLMTurnStart)                                               :884
 7. invokeLlmWithOverflowRetry()       ← 流式 + eager dispatch，见 7.4/7.5  :886
 8. publish(LLMTurnEnd)                ← 带 prompt/completion tokens     :891
 9. setCompletedTurnCount(turn + 1)                                     :896
10. cancelToken.isCancelled()          ← LLM 返回后、tool 执行前         :899
11. 解析 toolCallReqs                  ← 优先 readySegments，fallback parser  :904
12. 【无 toolCall】resolveGuardedFinalOutput() → OutputGuard
      → appendAssistantMessage + SessionComplete + SegmentComplete
      → return Completed                                                :913
13. 【有 toolCall】appendAssistantMessage（含 tool_calls）
14.   for tc in toolCalls: 顺序执行（见 7.7）                            :962
15. 追加 StepAdvanced 事件（advance_step 产生的，批量写）                 :1074
16. composeToolDefs() 重算            ← Skill 激活 / 切组可能已改变       :1078
17. 构建下一轮 ContextAssemblyRequest  ← phase=POST_TOOL_EXECUTION       :1093
18. fireCheckpoint(POST_TURN)                                           :1113
```

## 7.4 LLM 调用的 StreamPipeline

`LlmInvocation.invoke()`（`:166`）构建 `ChatRequest`，然后：

```java
for (ChatChunk chunk : llm.stream(request).toIterable()) {
    sctx.markChunkSeen();
    if (chunkInterceptor != null) cont = chunkInterceptor.onChunk(chunk);
    pipeline.onChunk(chunk);
    if (!cont) break;
}
```

handler 链（`:227`）：

```
EventLogHandler          → 应用 OutputFilter，推 PushEvent.TextDelta
ToolEventLogHandler      → 推 PushEvent.ToolCallStart
ToolCallHandler          → 按 ToolArgReadiness（默认 JsonCompleteReadiness）判断参数 JSON 是否完整
[EagerDispatchHandler]   → 完整即刻异步派发（可选）
TextAccumulationHandler  → 累积完整文本
ReasoningAccumulationHandler → 累积 reasoning_content
MetricsHandler           → 打点
```

默认参数：`temperature` 缺省 `0.1`，`maxTokens` 缺省 `4096`（`LlmInvocation.java:168`）。

## 7.5 Eager Dispatch：消除决策到执行的真空

**是什么**：LLM 流还没结束，某个 tool call 的参数 JSON 一旦检测到完整，立刻在后台线程开跑，不等整个流结束。

```java
// LlmInvocation.java:197
EagerDispatchHandler eagerHandler = new EagerDispatchHandler(
    readinessLookup,
    segment -> CompletableFuture.runAsync(
        AgentContextPropagation.wrap(() -> {
            ToolExecutionResult result = toolInvocation.executeTool(...);
            eagerResults.put(segment.toolCallIndex(), result);
        }), eagerExecutor),
    segment -> resolveToolTimeoutMs(segment.name()),        // 默认 30_000ms
    (segment, ex) -> eagerResults.put(segment.toolCallIndex(),
        new ToolExecutionResult.Completed(ToolResult.timedOut(callId))));
```

- 线程池：core=4 / max=200 / queue=1000（`DefaultAgentLoop.java:183`）
- 上下文用 `AgentContextPropagation.wrap()` 透传（OTel Context）
- 结果落 `ConcurrentHashMap<Integer, ToolExecutionResult>`，key = toolCallIndex
- 流处理完后 `eagerHandler.awaitAll()`（`:270`）

**为什么引入**：源码注释（`DefaultAgentLoop.java:173`）明确提到 mioclaw eval 观测到「LLM 决策完 → tool 真发起」之间 P95 ≈ 80s 的时间真空。

**主循环怎么消费**（`:994`）：

```java
ToolExecutionResult eagerExec = llmTurn.eagerResults().get(tc.index());
if (eagerExec != null) {
    toolExecution = patchEagerMetadata(eagerExec, ...);       // 只修 index/callId/toolName/assistantMsgId
} else if (llmTurn.eagerDispatchedIndices().contains(tc.index())) {
    toolExecution = ...error("[EAGER] tool execution did not complete within 30s timeout");
} else {
    toolExecution = executeTool(...);                          // 未 eager 派发 → 同步执行
}
```

`patchEagerMetadata`（`:2262`）**不重新渲染 result 文本**——否则 `SuccessWithData` 自渲染的 content 会被覆盖。

## 7.5.1 413 上下文溢出重试

`invokeLlmWithOverflowRetry()`（`:2171`）：

```java
while (true) {
    try { return invokeLlm(..., messages, est, ...); }
    catch (LlmException e) {
        if (!isContextOverflow(e) || !overflowRetryEnabled(req) || retries++ >= retryMax) throw e;
        AssembledContext re = forceCompactAndReassemble(req, CompactionPhase.OVERFLOW_FALLBACK);
        messages = re.messages(); est = re.estimatedTokens();
    }
}
```

`isContextOverflow`（`:2146`）：statusCode ∈ {400, 413} **且** body 命中 7 个 marker 之一（`context_length_exceeded`、`context length`、`maximum context`、`context window`、`prompt is too long`、`input is too long`、`too many tokens`、`reduce the length`）。

**幂等保证**（`:2167`）：只重试「请求期被拒、流未开」的场景。`LlmInvocation` 在「流中才溢出」（`sctx.sawAnyChunk()`）时把异常包成不可重试形式抛出（`:254`）——否则会向用户重复吐字。

配置：`agentDef.contextConfig().overflowRetryOn413()`（默认 false）、`overflowRetryMax`（默认 1）。

## 7.6 ToolCallHistory：死循环检测

`agent/ToolCallHistory.java`：

```java
static final int DEFAULT_WINDOW    = 10;   // 滑动窗口
static final int DEFAULT_THRESHOLD = 3;    // 连续重复阈值 K
```

三种模式（优先级 Repeat > PingPong > NoProgress，`:155`）：

| 模式 | 判定 | 行 |
|-|-|-|
| `RepeatDetected` | 最近 K 次同一 tool 且 argsHash 相同 | `:88` |
| `PingPongDetected` | 最近 2K 次严格交替 A→B→A→B（A≠B，argsHash 可不同） | `:111` |
| `NoProgressDetected` | 最近 K 次同一 tool 且 **resultHash** 相同 | `:134` |

**argsHash**（`DefaultAgentLoop.java:2024`）：先 `ToolArgCoercer.coerce()` 按 schema 矫正类型（`"42"` → `42`，`"true"` → `true`），再取 hashCode。这样 LLM 换个类型写法不会绕过检测。

**resultHash**（`:2033`）优先级：`structuredData` > `resources` > `contents` > `result` 字符串。前三者用 key 排序后的稳定序列化再 hash（避免 Map 顺序差异）。

**处置**（`:868`）：

```java
if (!(loopResult instanceof DetectionResult.None)) {
    consecutiveLoopWarnings++;
    if (consecutiveLoopWarnings >= DEFAULT_THRESHOLD + 2) {   // >= 5
        return failSession("TOOL_LOOP", buildLoopFailMessage(loopResult));
    }
    llmMessages = augmented(llmMessages, Message.user(buildLoopWarning(...)));
}
```

即：**第 1 次检测到 → 注入中文警告消息给 LLM「你已连续 3 次用相同参数调用 X，请换个方向」；累计到第 5 次 → 直接 fail**。只有某轮检测结果为 `None` 时才清零。

Preflight 工具（callId 以 `preflight_` 开头）**不记录**进 history（`:1063`）。

## 7.7 工具执行

**顺序 vs 并行**：主循环里 `for (ToolCall tc : toolCalls)` 是**串行**的。唯一的并行路径是 eager dispatch（LLM 流式期间）和 preflight（`:2377`，`CompletableFuture.supplyAsync` + `eagerExecutor`）。

**特殊工具优先分流**（`:976`）：

| 工具名 | 处理 |
|-|-|
| `advance_step` | `handleAdvanceStep()` → 步骤状态机转换，返回 `CompletedWithStepAdvance` |
| `switch_tool_groups` | `handleSwitchToolGroups()` → 改 `ctx.params(ACTIVE_GROUPS)` 并重解析工具列表 |
| `compact_context` | `handleCompactContext()` → `session.requestForceCompaction()` 置一次性强制压缩标记 |
| Skill 内置工具 | `executeBuiltinTool()` |
| 其余 | eager 结果 / 同步 `executeTool()` |

**executeTool 流程**（`ToolInvocation.java:95`）：

```
1. allowedTools.contains(name)?     否 → error("Tool not allowed")
2. toolRegistry.find(name)          null → error("Tool not found")
3. 构建 ToolContext（sessionId/callId/segmentId/userId/permissionContext/agentId/
                    skillActivationPort/configContext/runtimeContext）
4. applyBeforeToolCallPolicies()    ← ctx.executionPolicies() 逐个 beforeToolCall
5. ctx.toolExecutor().execute()     ← 经 interceptor/审计/rate-limit 链
   （无 executor 时 fallback tool.invoke(argsMap, toolCtx)）
6. mapToolResult()                  ← ToolResult → ToolExecutionResult
```

**参数解析**（`ToolInvocation.java:219`）：Jackson 解析失败返回 `Map.of()`，**不抛异常**——不让参数解析失败阻断整轮。注意：实际传给 Tool 的仍是原始 JSON 字符串，`ToolArgCoercer` 的矫正结果**只用于 hash 计算和 policy 审批**。

**错误处理分级**：

- 工具不存在/不允许/执行抛普通异常 → 返回 error ToolResult，**继续循环**（LLM 下一轮会看到错误，有机会自我修正）
- `SandboxUnavailableException` → 穿透到主循环 → `failSession("SANDBOX_UNAVAILABLE")`（`:1132`）
- Eager 超时 → `ToolResult.timedOut(callId)`

**结果写回**（`AbstractAgentLoop.java:666`）：

```java
publisher.publish(new PushEvent.ToolCallResult(segmentId, callId, result, isError,
        contents, displayContents, errorOutput, idIndexSnapshot));
session.append(WorkspaceEvent.ToolResultMessage.builder()...build());
```

**afterToolCall + IdIndex**（`:1174` / `:1049`）：遍历 `ctx.executionPolicies()` 调 `afterToolCall`（IdIndexPolicy 在此更新 snapshot）；若 `agentDef.idIndexEnabled()` 则写回 `session.setLatestIdIndexSnapshot(...)` 保证跨 dispatch 连续。

## 7.8 工具结果的四级渲染

`renderToolResultForPrompt()`（`:1184`），在 `appendToolResult` 之前：

```
1. applyToolResultPreRenderProcessors()   :1213
     遍历注入的 toolResultPreRenderProcessors，逐步加工 structuredData
     支持 processor.supports(toolName) 门控
2. renderRegistryToolPatch()
     ContextRendererRegistry 把 renderData 渲染为 ContextPatch
     （contents / displayContents / renderDataPatch / ephemeralSystem）
3. renderToolDefinitionTemplate()
     toolDef.resultTemplate（Jinja）→ InlineTemplateContextRenderer
4. mergeDisplayContents() + applyMessageContents()
```

`ContextPatch` 的 `renderDataPatch` 和 `ephemeralSystem` 会被累积进 `accumulatedRdpList` / `accumulatedEphemeral`，在**下一轮 assemble** 时注入（`:1055`）——这是工具向上下文注入侧信道的标准通路。

## 7.9 终止条件全表

`ExecutionOutcome`（sealed）：`Completed` / `Waiting` / `Cancelled` / `Failed` / `ExitWithLabel`（仅 ChainedAgentLoop 产生）。

| 条件 | 结果 | 行 |
|-|-|-|
| LLM 不再返回 tool call | `Completed` | `:940` |
| maxTurns 耗尽后 summary 成功 | `Completed` | `:1499` |
| cancelToken 命中 | `Cancelled` | `:841` / `:900` |
| 工具返回 HITL/Async/Handoff/Child/Waitpoint | `Waiting` | `:1038` |
| `consecutiveLoopWarnings >= 5` | `Failed("TOOL_LOOP")` | `:872` |
| `finish_reason=tool_calls` 但无完整 JSON | `Failed("INVALID_TOOL_CALL_ARGUMENTS")` | `:915` |
| HITL checkpoint 捕获失败 | `Failed("HITL_CHECKPOINT_CAPTURE_FAILED")` | `:1032` |
| 有 agentDefPatch 时工具要挂起 | `Failed("UNSUPPORTED_OVERRIDE_WITH_CHECKPOINT")` | `:1021` |
| SandboxUnavailable / LlmException / OverflowExhausted / OutputGuard / 其它 | `Failed(对应 errorType)` | `:1132`–`:1144` |
| bootstrap 发现 pending 丢失 | `Failed("MISSING_PENDING_TOOL_CALL")` | `AbstractAgentLoop.java:880` |

**maxTurns 不是直接 fail**（`handleMaxTurnsExceeded():1402`）：

1. 用 `CompactionPhase.MAX_TURNS_FALLBACK` 重新 assemble（带收敛 summary prompt）
2. `session.isTransient()` → 直接拼 system messages 当输出（不调 LLM）
3. 否则用 summary ModelConfig 再调一次 LLM
4. 结果过 OutputGuard → `Completed`；summary 本身失败才 `Failed("MAX_TURNS_EXCEEDED")`

## 7.10 OutputGuard 与 Repair 子循环

`resolveGuardedFinalOutput()`（`:1792`），每次准备提交 final output 时调用：

```
config.enabled()==false → interceptLastAssistantMsg() 后直接返回
否则 while(true):
   interceptLastAssistantMsg()          发 AgentEvent.BeforeLastAssistantMessage
                                        Observer 可返回 AgentDecision.Modify 替换文本
   outputGuardHookChain.evaluateWithRepair()
     ├─ Accept   → 返回 canonical output
     ├─ Degrade  → 返回 fallback output（reasoning 置 null）
     └─ Repair
          预算内   → 追加 Assistant + User 修复提示，再调一次 LLM，repairAttempt++
          预算耗尽 → PASS_THROUGH 策略则直接返回当前 candidate
                     否则抛 OutputGuardException → Failed("OUTPUT_GUARD_ERROR")
```

Repair 循环内外都有 cancel 检查（`:1846` / `:1911` / `:1920`）。

## 7.11 挂起与恢复（HITL / Async / Handoff / 子 Agent）

| 场景 | 工具返回 | 写入事件 | 恢复入口 | pending 丢失时 |
|-|-|-|-|-|
| HITL | `NeedApproval` → `WaitState.HitlWait` | `HitlPending` | `HitlDecision` | **failSession**（状态损坏，快速失败） |
| 异步任务 | `Async` → `AsyncWait` | `AsyncTaskPending` | `AsyncTaskCompleted` | **容错**：用默认值 (idx=0, toolName=null) 继续（`AbstractAgentLoop.java:795` 有注释解释） |
| 异步超时 | — | — | `AsyncTaskTimedOut` | failSession（超时是「超时器对 pending 的决策」，语义近 HITL） |
| Handoff | `HandoffWait` | `HandoffSuspend` | `HandoffResume` | failSession |
| 子 Agent | `ChildAgentWait` | `ChildAgentSuspend` | `ChildAgentResume` | 容错 |
| Waitpoint | `WaitpointWait` | `WaitpointSuspend` | `WaitpointResume` | 由 Router 层处理 |

**HITL 挂起前的 checkpoint 捕获**（`captureHitlCheckpoint():255`）：

- codec/store 任一未配置 → 保留旧内存态 HITL
- 都配置 → 序列化 RuntimeContext 快照，checkpointId 含 `UUID.randomUUID()` 保证每次挂起唯一，写回 `HitlWait.checkpointId`

**恢复后的渲染补偿**（`renderBootstrapToolResult():672`）：恢复时对工具结果应用与主循环相同的 afterToolCallPolicies + IdIndex + Jinja 渲染，保证 resume 后侧信道（renderDataPatch / ephemeralSystem）不丢。

## 7.12 Checkpoint

`fireCheckpoint()`（`:214`），三个时机：`PRE_TURN`（`:883`）、`POST_TURN`（`:1113`）、`COMPLETED`（`:939`/`:1499`）。

```java
if (ctx.agentDefPatch() != null || !ctx.ephemeralSkills().isEmpty()) {
    // per-request state 不支持序列化 → 跳过持久化，只触发用户回调
} else {
    checkpointCodec.serializeStrict(ctx, null);
    checkpointStore.save(checkpointId, snapshot);        // id = sessionId:requestId:TYPE:turn
    publisher.publish(new PushEvent.CheckpointCreated(...));
}
```

## 7.13 AgentDef / Skill / ToolGroup 三种动态性

**AgentDef 三层优先级**（`:658`）：

```
原始 ctx.agentDef()
  → agentDefResolver.resolve(...)      dimension overlay（实验分桶/灰度）
  → AgentDefOverlay.apply(patch)       per-request patch（PE 平台/评测）
```

**Skill 可见性**：`skillRouter.resolveVisibleSkills()` 决定本次哪些 skill 可见（默认 `StaticSkillRouter` 返回 `agentDef.skillRefs()`）。

**D5 shadow**（`:760` / `:1592`）：ephemeral skill 会**遮蔽**同名全局 skill，从 visibleSkills 过滤掉，防止 `autoActivate` 激活全局版本并泄漏其 `requiredTools`。

**Skill 解锁工具**（`unionWithSkillRequiredTools():1610`）：遍历 `session.activeSkillProjection()`，把版本匹配的非 lazy PromptSkill 的 `requiredTools` 并入 `baseAllowedTools`。

**ToolGroup 切换**（`:1642` / `:1658`）：

```java
// 每轮 composeToolDefs 时
Set<String> activeGroups = ctx.params(ToolGroupParams.ACTIVE_GROUPS);
effective = ToolGroupFilter.computeEffective(baseAllowedTools, cfg, activeGroups);
// LLM 调用 switch_tool_groups 时
activeGroups.clear(); activeGroups.addAll(toActivate);
```

下一轮 `composeToolDefs()` 重算，新组工具即对 LLM 可见。这让「一个 agent 挂 100 个工具但每轮只暴露 20 个」成为可能。

## 7.14 主循环全景图

```
AbstractAgentLoop.run()  [final]
  ├─ setCompletedTurnCount(0) / OTel / CAT / LoopStarted
  └─ runInternal()
       ├─ resolveEffectiveAgentDef()      dimension overlay + per-request patch
       ├─ allowedTools / initToolGroups / IdIndex 恢复
       ├─ bootstrapSessionState()         HITL/Async/Child/Handoff 恢复
       ├─ SkillRouter + autoActivate + D5 shadow
       ├─ composeToolDefs()               ToolGroupFilter + ToolPermissionFilter
       ├─ runPreflightStage()             并行，结果写事件日志
       └─ for turn in [0, maxTurns):
            cancel? → Cancelled
            resolveTurnLlm()
            contextEngine.assemble()      ← 第 8 章
            loopHistory.detect(K=3)       ← 注入警告 / >=5 次 fail
            fireCheckpoint(PRE_TURN) + LLMTurnStart
            invokeLlmWithOverflowRetry()
               └─ LlmInvocation → StreamPipeline → eager dispatch → awaitAll
            LLMTurnEnd
            cancel? → Cancelled
            ├─ 无 toolCall → OutputGuard → Completed
            └─ 有 toolCall → appendAssistantMessage
                 for tc: 特殊工具分流 / eager 结果 / 同步执行
                         → Waiting? → checkpoint + appendWaitingEvent → Waiting
                         → afterToolCallPolicies + IdIndex
                         → renderToolResultForPrompt（PreRender→Renderer→Jinja）
                         → appendToolResult（PushEvent + WorkspaceEvent）
                         → loopHistory.record()
                 composeToolDefs() 重算
                 下一轮 ContextAssemblyRequest
                 fireCheckpoint(POST_TURN)
       └─ handleMaxTurnsExceeded()        summary LLM → Completed / Failed
```

---

# 第 8 章 ContextEngine：上下文组装、压缩与渲染

这是 Leto 里代码密度最高的子系统（`DefaultContextEngine` 约 1600 行）。它回答一个问题：**「这一轮到底给 LLM 喂哪些 message？」**

## 8.1 接口极简，实现极重

```java
public interface ContextEngine {
    AssembledContext assemble(ContextAssemblyRequest request);
    default AssembledContext preWarm(ContextAssemblyRequest request) { return assemble(request); }
}
```

AgentLoop 只认识 `ContextEngine`，**不直接接触**`PromptAssembler` / `Compactor` / `Renderer`。`preWarm` 是「工具执行期间预组装 L1–L4 不变前缀」的钩子，默认退化为完整 assemble。

**输入 `ContextAssemblyRequest`** 关键字段：

| 字段 | 用途 |
|-|-|
| `session` | 历史消息来源 |
| `agentDef` | identity / maxTurns / toolRefs / **contextConfig** |
| `availableTools` | 已过滤好的工具定义（要算进 token） |
| `ephemeralInjection` | 本次请求级动态注入 |
| `compactionPhase` | 触发压缩的阶段（null → `POST_TOOL_EXECUTION`） |
| `features` | FeatureNode 产出的业务特征 KV（模板变量 `user.*` / `query.*`） |
| `renderDataPatch` | 工具结果侧信道模板变量（`turn.context`） |
| `contentsInjection` | 工具结果侧信道 contents |
| `effectiveLlmClient` | 主对话模型，供摘要 endpoint 回退 |
| `reasoningReplayBuffer` | msgId → reasoningContent 回放缓存 |

**输出 `AssembledContext`**：

```java
List<Message> messages;      // 不可变，首条必须是 system
String specId;               // L1+L2 内容哈希 → KV cache key
int estimatedTokens;         // 含 tool schema + 每条 message 4 token wrapper + summary 增量
Map<String, Object> metadata;
```

## 8.2 assemble() 的 14 个阶段

```
request
 0. 原子读取 prevMeta 快照            ← session.compactionMeta()，全程共用，绝不中途重读
 1. per-agent config 解析             ← agentDef.contextConfig() 优先于构造期 defaultConfig
 2. toPromptInput() → PromptAssembler.assemble()   ← 六层 ContextLayer 管道（8.3）
 3. assignIds()                       ← 基于内容指纹分配稳定 messageId
 4. computeProtectedPositions + pinnedMessages
 5. CompactionTrigger.evaluate()      ← 三档决策 NONE / ASYNC / SYNC（8.4）
      NONE  → assembleNoCompact（只注入上轮 summary）→ 返回
      ASYNC → maybeCompactAsync 后台提交 → assembleNoCompact → 返回
      SYNC  → 继续
 6. 构建 CompactionRequest
 7. compactor.compact()  或  compactHardBand()     ← hard 档兜底链（8.5）
 8. updateCompactionMeta + session.appendCompactionSummary()
 9. PruneApplier.apply()              ← 按 PruneDirective 物理删除 / tier 改写
10. filterCompactedMessages()         ← 移除 0 < pos <= cutoff 且非 protected 的消息
11. injectCompactionSummary()         ← P0 单条 / P2 双摘要，位置有讲究（8.6）
12. mergeSystemMessages()             ← 所有 SYSTEM 用 \n\n 拼成一条置首
13. hard 档后验                        ← 重新估 token，仍超窗 → ContextOverflowExhaustedException
14. injectMaxTurnsFallbackHint()      ← MAX_TURNS_FALLBACK 阶段追加收敛指令（不写 Session）
```

### 阶段 0 为什么重要

```java
Session.CompactionMeta prevMeta = (session != null) ? session.compactionMeta() : null;
```

入口**单次**读取，后续 filter / trigger / inject / prune 全部共用这一份快照。因为异步压缩可能在中途落库，如果各阶段各读一次，会拿到「过滤用旧 cutoff、注入用新 summary」这种撕裂视图。

### 阶段 4：谁不能被压

```java
Set<Long> protectedPositions = computeProtectedPositions(indexedMessages, config.protectFirstN());
List<Message> pinnedMessages = indexedMessages.stream()
    .filter(m -> m.getRole() == SYSTEM
        || m.getMeta().containsKey("preflight.provider")
        || lastUserMsg.map(u -> u.getId().equals(m.getId())).orElse(false)
        || protectedPositions.contains(m.getPosition()))
    .toList();
```

「当前轮 user」按 **position 最大的 USER 消息**识别，而**不是**列表最后一条 USER——因为 `contentsInjection` 会在尾部注入 USER 角色消息，按 list-last 会认错人。

`protectFirstN`（默认 3）保留最早 N 条 `pos>0` 的原文进保护区，与 CROSS_TURN summary **有意重叠**（开头几轮往往定调，摘要 + 原文双保险）。

## 8.3 六层 ContextLayer 管道

```java
public interface ContextLayer {
    int order();                                 // L1=100 … L6=600
    List<Message> assemble(PromptInput input);   // 纯函数，必须无状态、线程安全
}
```

`LayeredPromptAssembler` 构造时把内置层 + 业务扩展层按 order 排序冻结。

| order | 层 | 职责 |
|-|-|-|
| 100 | `InvariantLayer`（**可选，业务注入**） | 跨 agent 不变的全局前缀 |
| 200 | `AgentDefinitionLayer` | Agent identity / PromptSpec（Jinja2 渲染）/ StepDef / PLAN_SUMMARY phase |
| 250 | `EphemeralSkillInstructionLayer` | 本次请求级 ephemeral skill 指令 |
| 300 | `ActiveSkillInstructionLayer` | session 级已激活 skill 指令（按**冻结版本**重投影，防热更泄漏） |
| 350 | `SkillCatalogLayer` | lazy skill 摘要列表（LLM 按需激活） |
| 500 | `SessionContextLayer` | 历史对话消息 |
| 600 | `PerTurnRecallLayer` | 本轮 recall（Memory、最近工具结果） |

**L1–L3（order < 300）构成 KV cache 稳定前缀**，`specId` 只 hash 这一段。这是 prompt caching 命中率的关键——把会变的东西（历史、recall）放后面。

`AgentDefinitionLayer` 三种模式：

1. 有 PromptSpec + RenderEngineRegistry → 完整 Jinja2 渲染
2. 有 PromptSpec + 单 RenderEngine → 基础 `{{var}}` 替换
3. 无 PromptSpec → 退化为 `identity` 字符串（缺省 `"你是一个智能助手，直接回答用户问题。"`）

模板变量树 `RenderData` 三个命名空间：`user.*` / `query.*`（来自 `features`）、`turn.context`（来自 `renderDataPatch`）。

## 8.4 CompactionTrigger：三档决策

```java
public interface CompactionTrigger {
    boolean shouldCompact(CompactionTriggerContext ctx);        // 旧 boolean ABI
    default CompactionMode evaluate(ctx) {                       // 新三档 ABI
        return shouldCompact(ctx) ? SYNC : NONE;
    }
    default boolean bandAware() { return false; }
    static CompactionTrigger anyOf(CompactionTrigger... ts);     // 取档 max
}
enum CompactionMode { NONE, ASYNC, SYNC }
```

> **坑**：只实现旧 `shouldCompact` 的触发器，经默认 `evaluate` 桥接**永远产不出 ASYNC 档**。要用异步压缩必须 override `evaluate` 且 `bandAware()` 返回 true。

决策函数：

```java
if (phase == OVERFLOW_FALLBACK) return (compactNow=true,  hard=true,  async=false);  // 413：必须硬压
if (phase == MANUAL)            return (compactNow=true,  hard=false, async=false);  // 手动：best-effort
CompactionMode mode = trigger.evaluate(ctx);
if (mode == NONE)               return (false, false, false);
if (mode == ASYNC && asyncRunnable) return (false, false, true);
return (true, trigger.bandAware() && mode == SYNC, false);
```

三档互斥。`CompactionPhase` 五种：

| 值 | 语义 |
|-|-|
| `PRE_FIRST_TURN` | 首次 assemble，通常不压 |
| `POST_TOOL_EXECUTION` | **主战场**：工具执行后、下轮 LLM 前，按 token 阈值 |
| `MAX_TURNS_FALLBACK` | 超 maxTurns 兜底，激进 |
| `OVERFLOW_FALLBACK` | 413 被动兜底，强制 hard，不看阈值 |
| `MANUAL` | `compact_context` 工具主动触发，容忍 noop |

**异步压缩的 single-flight**：`ConcurrentHashMap.newKeySet()` 保证同一 session 只有一个在途异步压缩；提交 immutable snapshot 给有界 executor；双层超时（`orTimeout(compressTimeoutMs)` + LLM HTTP 内层超时）；提交被 AbortPolicy 拒绝时**立即解锁 single-flight，绝不偷跑**。

## 8.5 Compactor 与 hard 档兜底链

```java
public interface Compactor { CompactionResult compact(CompactionRequest request); }
```

纯同步，异步性完全由 `DefaultContextEngine` 的 executor 负责。

内置实现：

| 实现 | 注册名 | 调 LLM | 产 meta | 用途 |
|-|-|-|-|-|
| `BudgetThresholdTrigger`（trigger） | — | — | — | `currentTokens >= budget * 0.80` 触发 |
| `TruncatingCompactor` | `truncating` | 否 | **否** | 从后往前贪心保留，产 `drop` 指令。**绝不用于兜底链** |
| `SkeletonCompactor` | `skeleton` | **否** | **是** | 只留最近 `SKELETON_KEEP=4` 条非 pinned 原文，激进 cutoff，产占位摘要 |
| `LlmSummaryCompactor` | — | 是 | 是 | 通过 `SummaryClient` SPI 调 LLM 生成摘要 |

**hard 档确定性兜底链**：

```
1. 调主压缩器（通常 LlmSummaryCompactor）
2. compacted.compactionMeta() == null（noop）→ 调 SkeletonCompactor
3. skeleton 仍 noop → 抛 ContextOverflowExhaustedException
```

> **为什么 `TruncatingCompactor` 不能兜底**：它不产覆盖摘要，会在事件序列上留下「空洞」（被删的消息既没原文也没摘要），破坏 no-gap 约束——后续压缩算 cutoff 时会算错。
> 
> **`compactionMeta == null` 就是 noop 信号**：soft 档下 noop 无害（cutoff 不推进）；hard 档下 noop 意味着「必须压但没压动」，必须进兜底链。

摘要 endpoint：`config.summarizationModel()`，为空时回退本轮主模型（`CompactionRequest.effectiveLlmClient()`）。摘要失败返回 `summaryFailed=true`，soft/async 安全降级，hard 档由兜底链接管。

`PruneDirective` 两种形态：`drop(messageId)` 直接删；`tier(messageId, tier)` 改写为低保真档（业务自定义 tier 名，如 `"LIGHT"`/`"SKELETON"`）。合约：同 messageId 多指令取最后一个；引用不存在的 id 静默 warn；pinned 上的指令被 Applier 忽略。

## 8.6 摘要注入的位置学问

`Session.CompactionMeta` 关键字段：`compressedUntilPosition`（cutoff，inclusive）、`version`（单调递增）、`tokenCount`（**摘要自身** token，不是整体）、`summary`、`effectiveRole`、`originalCount`、`metadata`（P2 扩展槽）。

注入规则：

- **P2 双摘要**（`hasInTurnSummary()` 且 turnBound 有效）：CROSS_TURN 摘要注入在 turnBound user **之前**，IN_TURN 摘要注入在其**之后**。这个顺序经过论证，不会破坏 `tool_use ↔ tool_result` 的配对。
- **P0 单条**：找第一条 `pos > cutoff` 的消息，插在它前面；找不到则插到最后一条无 position 的消息之后。
- summary 消息打 `COMPACTION_SUMMARY_META_KEY` 标记且**不设 position**，使它自己不会被下一轮压缩误判。
- SYSTEM 角色的 summary 会被 `mergeSystemMessages` 自动并到首条。

**写序约束（重要）**：`session.appendCompactionSummary(newMeta)` 必须**先 append 到共享事件日志**（触发 DB 写 `session_message_summary`），成功后才推进热缓存的 `compactionMeta` / `pruningPosition`。反过来会出现「本机认为已压、其它 pod 读不到摘要」。

`version` 单调递增：metadata patch 路径强制 `max(cm.version, prev+1)`，防止 position-max 仲裁把新版本丢弃。

## 8.7 Token 估算

```java
public interface TokenEstimator {
    int estimate(String text);
    default int estimate(String text, @Nullable String modelHint) { return estimate(text); }

    int PER_MESSAGE_OVERHEAD_TOKENS      = 4;   // chat-template wrapper
    int TOOL_CALL_ID_FIELD_OVERHEAD_TOKENS = 4;
    int TOOL_CALL_JSON_OVERHEAD_TOKENS   = 8;

    default int estimateMessage(Message m, String modelHint) {
        return estimateMessageBody(m, modelHint) + PER_MESSAGE_OVERHEAD_TOKENS;
    }
}
```

> **红线**：压缩路径（BoundaryStrategy / 分块 / SkeletonCompactor / trigger）**必须**用 `estimateMessage(m, modelHint)`，**不能**用 `estimate(m.getContent())`。因为 `assistant(tool_use)` 消息的 `content` 是 null，后者会把它估成 0 token，导致「明明塞满了 tool_calls 却判定没超预算」。

三种实现：

| 实现 | 算法 | 适用 |
|-|-|-|
| `approximate()` | `(len + 3) / 4` | 仅 ASCII，中文严重低估 |
| `unicodeBlock()` | CJK 1.1 / ASCII 0.25 / 其它 Unicode 0.6 / 空白 0.1 token per char | 无依赖场景，误差 ±20%，**宁多裁不欠裁** |
| `JtokkitTokenEstimator` | jtokkit（tiktoken Java port）+ `ModelEncodingRouter` 按 modelId 路由词表 | 生产推荐 |

`ModelEncodingRouter` 路由表：Qwen/Doubao/DeepSeek → `P50K_BASE`（实测 Qwen3 ratio ≈ 1.005）；GPT-4o/o1/o3 → `O200K_BASE`；GPT-4/3.5/Claude → `CL100K_BASE`。Lazy 加载 + `ConcurrentHashMap` 缓存。

**Tool 定义也要算 token**：把 `List<ToolDefinition>` 序列化成 OpenAI function calling JSON 后整体估算，序列化失败 fail-soft 返回 0。

**Summary 增量估算**：SYSTEM 角色的 summary 要「先算现有 → 追加后重算 → 取差值」，因为 SYSTEM 合并会影响 wrapper 开销；非 SYSTEM 按段数 × (body + 4)。

## 8.8 CompactionSummaryFormatter：口径一致性

```java
@FunctionalInterface
public interface CompactionSummaryFormatter {
    String format(String summary);
    static CompactionSummaryFormatter identity() { return s -> s; }
}
```

业务方可以把摘要包成 `<previous_context>...</previous_context>`。`DefaultContextEngine` 通过 `assembler.compactionSummaryFormatter()` 获取同一个 formatter，保证：

1. `estimateDetachedSummaryTokens` 估的是 formatted 后的文本
2. `buildSummaryMessage` 注入的也是 formatted 后的文本

否则触发判断时不含格式化开销，注入后实际超窗。

## 8.9 ContextRenderer SPI

```java
public interface ContextRenderer {
    String entityType();      // 对应 ContentPart.EntityPart#entityType() 或 Tool#getName()
    @Nullable ContextPatch render(Object entity, Map<String,Object> ext, RendererContext ctx);
}
public interface ContextRendererRegistry {
    ContextPatch render(String entityType, Object entity, Map<String,Object> ext, RendererContext ctx);
    void register(ContextRenderer renderer);   // 后注册覆盖先注册
    @Nullable ContextRenderer find(String entityType);
}
```

`ContextPatch` 提供三条可组合的注入通道：user message 内容块、system 临时注入、模板变量（renderDataPatch）。

路由优先级：

1. 精确匹配已注册的业务渲染器
2. `USER_INPUT` 来源无匹配 → 框架内置 `TemplateContextRenderer`（从 `AgentDef#templateRefs()` 取模板）
3. `TOOL_RESULT` 来源：registry 只管业务自定义；`ToolDefinition.resultTemplate` 是 AgentLoop 层的框架级 fallback

Renderer 实现必须**无状态（单例注册），渲染失败记 warn 返回降级，不抛异常**。

## 8.10 观测事件

`sealed interface ContextEvent`：`BeforeCompaction` / `AfterCompaction`（带 summaryLatencyMs、summaryFailed、cutoffAdvance）/ `AsyncCompressionSubmitted` / `Completed` / `Discarded`（timeout|failed|noop|rejected）/ `ConvergenceTriggered` / `CompactionFallbackEngaged`（主压缩器 noop → skeleton 接管）/ `CompactionExhausted`（兜底链打穿）/ `CompactionOverflowRetry`。

排障时先看这几个事件的埋点，能直接区分「没触发压缩」「压了但 noop」「压了还是超」三种情况。

---

# 第 9 章 LlmClient：模型访问抽象

## 9.1 接口

```java
public interface LlmClient {
    ChatResponse chat(ChatRequest request);        // 同步阻塞
    Flux<ChatChunk> stream(ChatRequest request);   // 流式（Reactor）
}
```

实现必须线程安全，同一实例可并发使用。

## 9.2 ChatRequest

必填：`model`（非空非 blank）、`messages`（非空）。其余重要字段：

| 字段 | 说明 |
|-|-|
| `temperature` / `topP` / `topK` / `maxTokens` | 采样参数 |
| `tools` | function calling 工具列表；**null/空则不向 LLM 传 tools 字段** |
| `toolChoice` | sealed：`Auto` / `None` / `Required`（Anthropic 映射为 `"any"`）/ `Named(name)` |
| `enableThinking` / `thinkingBudget` | Anthropic extended thinking，budget 默认 4000 |
| `parallelToolCalls` | 是否允许并行 tool calls |
| `streamOptions` | 如 `{"include_usage": true}` |
| `customBody` | 配置层固定注入，**优先级最高，覆盖所有显式参数** |
| `providerExtras` | provider 特殊字段透传 |
| `options` | `RequestOptions{timeout, tracingTags, modelOverrides}` |
| `extraHeaders` | 每次调用的额外 HTTP header |
| `agentId` / `sessionId` / `requestId` | metrics 归因 + trace 关联 |
| `estimatedTokens` | PromptAssembler 的预估值，用于**校准 estimator 的实际误差** |
| `previousResponseId` | Responses API 服务端上下文管理 |

## 9.3 ChatChunk：流式 chunk 模型

严格对齐 OpenAI Chat Completions 流式协议 + Kimi/DeepSeek 扩展：

```java
String id, object, model;  long created;
String role;                    // 首帧 "assistant"，后续 null
String content;                 // 文本增量
String reasoningContent;        // 推理链增量（Kimi/DeepSeek 扩展）
ReasoningContentFormat reasoningContentFormat;
List<ToolCallDelta> toolCalls;
String finishReason;            // null = 进行中
Usage usage;                    // 仅最后一帧可能返回
```

**`ToolCallDelta` 的组装规则**（由消费方 StreamPipeline 实现）：

```java
int index;                 // 标识同一次 tool call 的分片序号 —— 分组 key
String id;                 // 首分片携带
String functionName;       // 首分片携带
String functionArguments;  // 每帧追加，字符串拼接
```

按 `index` 分组 → `id`/`functionName` 取首个非 null → `functionArguments` 逐帧拼接 → 直到 `finishReason == "tool_calls"` 才算完整。

**`ReasoningContentFormat`**：

| 值 | `openAiChatReplayCompatible()` | 说明 |
|-|-|-|
| `UNKNOWN` | false | 未声明/混合/自定义 |
| `OPENAI_CHAT_REASONING_CONTENT` | **true** | 顶层 `reasoning_content`，可进历史回放 |
| `ANTHROPIC_THINKING` | false | 缺 block/signature，不能用 OpenAI 字段回放 |

只有 `openAiChatReplayCompatible()==true` 的 reasoning 才允许写进历史消息重放。

**`Usage`**（含 Langfuse 规范扩展）：`promptTokens` / `completionTokens` / `totalTokens` / `cacheCreationInputTokens`（Anthropic 写缓存）/ `cacheReadInputTokens`（cache_read 或 OpenAI cached_tokens）/ `reasoningOutputTokens`（o1/o3/gpt-5）。

## 9.4 端点/模型选择

```java
public interface LlmClientFactory {
    LlmClient getOrCreate(LlmEndpointConfig config);      // 按 config 缓存，避免重复建 WebClient
    default LlmClient resolve(ConfigContext cfg, LlmClient defaultClient) { return defaultClient; }
}
```

`DefaultLlmClientFactory`：

- `ConcurrentHashMap<LlmEndpointConfig, LlmClient>` 缓存
- `resolve()` 先读 `baseLlmProvider`，再用 `ConfigOverlays.effective()` 解析 version overlay，组装 `LlmEndpointConfig` 后 `getOrCreate()`
- 按 `config.provider()` 路由：OpenAI 兼容 → `HttpLlmClient`（WebClient + OkHttp）；Anthropic → `AnthropicLlmClient`；Bedrock → `RunwayBedrockLlmClient`；Responses API → `ResponsesApiLlmClient`

**会话粘性**：`LlmEndpointConfig.hashRouteBySession=true` 时挂 HRW（一致性 hash）负载均衡，`AbstractLlmClient` 把 `sessionId` 作为 hash key 注入 WebClient attribute。三层开关（LB 算法 + attribute + ThreadLocal）保证一致性。作用是同一会话尽量打到同一后端实例，提高 prompt cache 命中率。

`HttpLlmClient` 细节：SSE 每帧 `"data: "` 前缀，`"data: [DONE]"` 结束；同步走 OkHttp；多模态通过 `LlmMultimodalAdapter`（`OpenAiMultimodalAdapter` / `QwenMultimodalAdapter`）把 `MessageContent.Image/Audio/Video` 转成 provider 格式；`customBody`**最后合并、覆盖一切**。

**Failover**：

```java
public static boolean shouldFailover(FailoverPolicy policy, Throwable ex) {
    if (ex instanceof LlmException llmEx) {
        if (policy.triggerOnAnyLlmException()) return true;
        return code != null && policy.triggerOnStatus().contains(code);
    }
    // 非 LlmException：按 triggerOnExceptions 全限定类名（含父类链）匹配
}
```

**LlmDecision**（`BeforeLLMCall` 拦截）：`Proceed(ChatRequest)`（可携带改写后的 request）/ `Block(reason)`。

---

# 第 10 章 Tool 子系统

## 10.1 Tool 接口

```java
public interface Tool {
    ToolDefinition getDefinition();                                  // 优先于 @ToolDef 注解
    ToolResult invoke(Map<String,Object> params, ToolContext context);
    default CompletableFuture<ToolResult> invokeAsync(...) { ... }   // 默认 commonPool 包装
}
```

`getDefinition()` 返回 null 时退回 `AnnotationToolDefinitionResolver` 解析 `@ToolDef` + `@Param`。实现**必须线程安全**（单例共享）。

## 10.2 ToolDefinition / ParamSchema

`ToolDefinition`（Builder，不可变）：

| 字段 | 说明 |
|-|-|
| `name` | 唯一名，snake_case |
| `description` | 给 LLM 看的功能描述 |
| `parameters` | `List<ParamSchema>` |
| `source` | 来源标识，用于**按来源批量注销**（MCP 断连场景） |
| `resultTemplateName` / `resultTemplate` | 命名模板 / inline Jinja 模板，渲染 `StructuredSuccess` |
| `runtime` | `ToolRuntime`：本地 JVM 或沙箱 |
| `groups` | 工具组列表 |

`ParamSchema`：

| 字段 | 说明 |
|-|-|
| `name` / `type` / `description` / `required` | 基础 JSON Schema |
| **`entityType`** | **IdIndex 实体类型**：非空时框架/工具会把该参数里的短 ID（N1/U1）解析成真实 ID |
| `properties` / `items` / `enumValues` | object 子字段 / array 元素 schema / enum 约束 |

注解方式：

```java
@ToolDef(name = "note_read", description = "...", sandbox = false)
@Param(name = "note_id", type = "string", description = "...", required = true, entityType = "note")
```

## 10.3 Registry 三种形态

| 实现 | 用途 |
|-|-|
| `DefaultToolRegistry` | `ConcurrentHashMap` 存储，`register` 同名覆盖，`find` 未找到返回 null |
| `CollectionToolRegistrar` | Spring 下自动收集所有 `Tool` Bean 批量注册 |
| `CompositeToolRegistry` | 本地 registry + 多个 `McpToolRegistry` 聚合。`find` 本地优先；`listDefinitions` 用 `LinkedHashMap` 去重且本地优先；`unregister` 从所有 registry 删。以 `@Primary` 覆盖注入点，**AgentLoop 无感知** |

`unregisterBySource(source)`：MCP server 断连时按 `"mcp:{serverId}"` 批量摘除该 server 的全部工具。

## 10.4 ToolResult：六种终态

```java
sealed interface ToolResult permits
    Success,            // success(String) / success(List<MessageContent>)
    SuccessWithData,    // successWithData(String, Map)
    StructuredSuccess,  // structured(Map) —— 先留结构化事实，IdIndex merge 后按模板渲染
    Failure,            // failure(...) / failureWithOutput(...)
    NeedApproval,       // needApproval(reason, TimeoutPolicy) → Session 挂起等审批
    Async               // async(taskId, CallbackSpec, TimeoutPolicy) → Session 挂起等回调
```

`Success` 三个字段：`content`（文本）/ `contents`（多模态 `List<MessageContent>`）/ `entityResources`（IdIndex 实体资源）。  
`withEntityResources(...)` 是不可变更新方法，给成功结果附加实体资源。

## 10.5 ToolContext：入站元数据 + 出站归因

per-tool-call 粒度，`sessionId` 必填。两类语义：

1. **入站只读元数据**：`requestId` / `callId` / `callSource`（LLM|PREFLIGHT|API）/ `segmentId` / `agentId` / `userId` / `permissionContext` / `configContext` / `runtimeContext`（以 Object 存，避免循环依赖）/ `currentTurn`
2. **出站观测归因**：`setAttribute(k, v)` 写回，框架构造 `ToolEvent` 时读取（典型：SkillInfoTool 写 `ATTR_SKILL_ID`）

**`copy()` 的必要性**：并行 fan-out 时为每个 call 建独立副本，隔离 `attributes` 写入，否则 skill 归因会串写。

## 10.6 ToolExecutor 与事件拦截

```java
ToolResult execute(ToolCall call, ToolContext context);
List<ToolResult> executeAll(List<ToolCall> calls, ToolContext context);   // 批量并行
ToolResult executeWithTool(Tool tool, ToolCall call, ToolContext ctx);    // 绕过 registry
@Nullable ToolDecision checkBeforeToolUse(List<ToolCall>, ToolContext);   // 只拦截不执行
```

`DefaultToolExecutor.invokeTool()` 流程：

```
1. tryCacheGet()                → 命中则发 AfterToolUse(cached=true) 后返回
2. Cat Transaction + OTel Span
3. 发 BeforeToolUse（可拦截事件）
4. SandboxAwareTool? → invokeSandboxAware() ： tool.invoke()
5. 发 AfterToolUse（观测事件）
6. tryCachePut()（仅成功结果）
异常 → 发 ToolCallFailed → 返回 ToolResult.failureWithOutput
```

`executeAll` 并发：单 call 走主线程；多 call 用 `CompletableFuture` + OTel Context 传播，**每个 call `context.copy()`**；无自定义 executor 时用 `CONTEXT_COMMON_POOL`，任务以 `TaskHelper.wrapRunnable` 包裹（保 CAT/MDC/Scope/RpcContext）。

沙箱执行：`SandboxRuntime.acquire()` / `release()`，遇 404 则 `evictAndReacquire` 重试一次。

**三个 ToolEvent**：

| 事件 | 类型 | 关键字段 |
|-|-|-|
| `BeforeToolUse` | **可拦截**（`InterceptEvent`） | calls / context / permissionContext / source / skillId… |
| `AfterToolUse` | 观测 | results / toolSources / durationMs / agentId |
| `ToolCallFailed` | 观测 | call / error / durationMs |

**`ToolDecision`**（拦截决策）：

| 子类型 | `isProceed()` |
|-|-|
| `Allow(approvedCalls)` | true |
| `Modify(modifiedCalls, reason)` | true |
| `Deny(reason)` | false |
| `NeedApproval(reason)` | false |

## 10.7 缓存

```java
interface ToolCacheManager { get / put(key, result, ttl) / invalidate / isEnabled }
interface ToolCacheStrategy {
    String buildKey(toolName, params, context);   // 返回 null = 跳过缓存
    Duration getTtl(toolName, call, context);     // 动态 TTL，支持 per-tool
}
interface ToolCacheEligibility { ... }            // per-tool 缓存资格判定
```

三种实现：`NoopToolCacheManager`（总开关关，零开销）/ `LocalToolCacheManager`（Caffeine）/ `RedisToolCacheManager`（Jedis）。**只缓存成功结果**。

## 10.8 ToolGroup：动态工具池

```java
class ToolGroup { String groupId; String description; List<String> toolIds; boolean alwaysActive; }
class ToolGroupConfig { List<ToolGroup> groups; Set<String> initialActiveGroups; }
```

`ToolGroupFilter` 可见性规则（按优先级）：

1. 未被分入任何组 → **始终可见**
2. 所在组 `alwaysActive=true` → 可见
3. 在当前激活组中 → 可见
4. 其余 → 不可见

激活组存在 `RuntimeContext.params()["active_tool_groups"]`（`HashSet<String>`），生命周期跟随单次 `run()`。

## 10.9 权限过滤

```java
@FunctionalInterface
public interface ToolPermissionFilter {
    List<ToolDefinition> filter(List<ToolDefinition> defs, PermissionContext ctx);
    static ToolPermissionFilter allowAll();
}
```

框架以 `@ConditionalOnMissingBean` 注册默认 Bean。**注册级过滤 + 执行级拦截**构成两道防线。

## 10.10 IdIndexToolSupport

框架**不自动改写** arguments，工具主动调 helper：

```java
IdIndexSnapshot snapshot(ToolContext ctx);                       // 读 ctx attribute "idIndexSnapshot"
IndexedEntity  resolveEntity(ctx, rawId, entityType);
String         resolveNaturalId(ctx, rawId, entityType);
ArgumentResolution resolveArguments(ctx, rawParams, paramSchemas);   // {resolved, unresolved}
ToolDecision   handleArguments(ctx, rawParams, schemas, FailureMode);
```

`FailureMode` 三种：`INFORM_MODEL`（返回 Denied 告诉模型有未解析短 ID）/ `SILENT_DENY` / `FALLBACK_ORIGINAL`。

## 10.11 内置工具与 MCP

- 文件系统：`ReadTool` / `WriteTool` / `EditTool` / `GlobTool` / `GrepTool`
- Shell：`BashTool`（带 `ShellPolicy`）
- 沙箱版：`SandboxBashTool` / `SandboxPythonTool` / `SandboxNodeTool` / `SandboxTypescriptTool` 等
- 默认：`CalculatorTool` / `EchoTool` / `TimeTool`
- 记忆：`MemoryRetrievalTool`（工具名 `memory_retrieval`，作为 preflight tool 在 LLM 调用前自动注入记忆）
- 知识库：`KnowledgeRetrievalTool`
- **MCP**：`McpProxyTool` 把 MCP SDK 的 `McpSchema.Tool` 适配成 Leto `Tool`。`letoName` 可选 `{serverId}__{mcpToolName}` 前缀；`source = "mcp:{serverId}"`；通过 `Supplier<McpSyncClient>`**懒获取**客户端支持断线重连；`onConnectionError` 回调通知 `McpConnectionManager` 重连

`CompositeToolFacade extends Tool`：对 LLM 暴露单个 `ToolDefinition`，内部编排多个子 Tool，`getSubTools()` 供依赖分析和调试。

---

# 第 11 章 PushEvent：流式事件模型

## 11.1 两类事件的分野

```
SessionEvent (sealed)
├── WorkspaceEvent   领域事件，append-only，参与状态重建   category = DOMAIN
└── PushEvent        执行过程事件，不写 projection        category = EXECUTION / OUTPUT / LIFECYCLE
```

**判断标准**：这个信息重启后还需要吗？需要 → WorkspaceEvent；只是给前端看进度 → PushEvent。

## 11.2 全量 PushEvent

**OUTPUT 类**

| 事件 | 字段 | 时机 |
|-|-|-|
| `ContentDelta` | segmentId, text, finish | LLM 流式文本增量 |
| `ThinkingDelta` | segmentId, text | reasoning_content 增量 |
| `CustomPayloadEvent` | segmentId, payload（原始 JSON） | 透传帧，**绕过 RenderingPipeline** 直达前端 |

**LLM 类**

| 事件 | 字段 |
|-|-|
| `LLMTurnStart` | segmentId, turnIndex, model |
| `LLMTurnEnd` | segmentId, turnIndex, inputTokens, outputTokens, thinkingTokens, finishReason |

**TOOL 类**

| 事件 | 字段 |
|-|-|
| `ToolCallStart` | segmentId, callId, toolName, toolId, arguments? |
| `ToolArgDelta` | segmentId, callId, partialJson |
| `ToolCallAssembled` | segmentId, callId, toolName, arguments（流式组装完毕） |
| `ToolStepStart` / `ToolStepResult` | 工具内部 step |
| `ToolCallResult` | segmentId, callId, result?, isError, **contents**, **displayContents**, errorOutput?, **idIndexSnapshot** |
| `ToolCallCancelled` | segmentId, callId, reason |

> `ToolCallResult` 的三视图设计：
> 
> - `contents`：工具事实输出（raw），**永远下发**，旧 client 只认这个
> - `displayContents`：renderer 派生的展示视图，新 client 优先渲染，缺失时回退 `contents`
> - 原则是「display 优先、缺失回退 raw、**不并行渲染**」
> - `idIndexSnapshot`：本次结果 merge 后的最新快照，消费方据此把短 ID 映射回资源详情

**GRAPH 类**：`GraphExecStart` / `SuperstepBoundary` / `NodeExecStart` / `NodeExecEnd` / `GraphExecEnd`

**LIFECYCLE 类**

| 事件 | 说明 |
|-|-|
| `TurnComplete` | 一轮 ReAct 完成 |
| `SegmentComplete` | 一个渲染段完成 |
| `PhaseChanged` | PLAN/ACT 阶段切换 |
| `PlanShown` / `PlanUpdated` / `PlanStepProgress` | 规划相关 |
| **`Steered`** | 当前段因插话被打断收尾（**语义 ≠ SegmentComplete**，前端据此同卡续段） |
| `StreamComplete` | 整条流结束（status / output / reason） |
| `CheckpointCreated` | checkpointId 格式 `{requestId}:{type}:{turn}` |

**CONTROL 类**：`HitlRequired` / `HandoffStarted` / `SessionSuspended` / `RuntimeError` / `MessageQueued`

**SEGMENT 类（Agentspark 对接面）**

| 事件 | 说明 |
|-|-|
| `FragmentPushEvent` | 渲染段处理完毕，携带 DataFragment 列表；SSE/WebSocket 通道 |
| `ImPushEvent` | IM 长连推送专用（Celestial），与 FragmentPushEvent **物理隔离** |
| `ImSignalEvent` | IM 信号（如 finish=3），不带 fragments |
| **`SegmentResultPushEvent`** | **需要上游后处理的段，直接把原始 `SegmentResult` + `SearchContext` 交给 agentspark** |
| `StreamDonePushEvent` | 流式生成结束标记 |

## 11.3 DefaultPushEventPublisher

```java
private final AtomicLong seq;
private final CopyOnWriteArrayList<BiConsumer<PushEvent, Long>> listeners;
private volatile Object runtimeContext;

public long publish(PushEvent event) {
    long currentSeq = seq.getAndIncrement();
    for (var listener : listeners) {
        try { listener.accept(event, currentSeq); }
        catch (RuntimeException e) { log.error("Listener failed on seq={}", currentSeq, e); }
    }
    return currentSeq;
}
```

三点设计：

- `CopyOnWriteArrayList`：注册/退订少、publish 多，读路径完全无锁
- `AtomicLong seq`：即使没有监听者也分配序号，保证因果序
- listener 抛异常被吞（记 error 日志），**不影响后续 listener 和调用方**——一个订阅者挂了不能拖垮整条流

`runtimeContext` 由 `DefaultRouter` 自动 bind/unbind，listener 用 typed getter 取，避免魔法字符串。

## 11.4 多订阅者与跨 pod

`SessionPushPublisherRegistry`：`ConcurrentHashMap<sessionId, CopyOnWriteArrayList<PushEventPublisher>>`

```java
public PushEventPublisher merge(String sessionId, PushEventPublisher sink) {
    List<PushEventPublisher> sessionSinks = subscriptions.get(sessionId);
    return event -> {
        long seq = sink.publish(event);                        // 请求级（SSE writer）
        for (var s : sessionSinks) seq = Math.max(seq, s.publish(event));  // session 级（WS）
        if (relay != null) relay.fanout(sessionId, event);     // 跨 pod
        return seq;
    };
}
```

`subscriptionOnly(sessionId)`：即使本机无订阅者，跨 pod 开启时也返回可 fanout 的 publisher——**覆盖「执行在 A pod、WebSocket 连在 B pod」这个核心场景**。

`CrossPodPushRelay`：

- daemon 线程 `push-xpod-owner-renew` 周期续期本机活跃 session 的 owner TTL（长会话不丢推送）
- 首个订阅者 → `onLocalSubscribe`；全部退订 → `onLocalUnsubscribe`
- `deliverLocally` 处理跨 pod 收到的帧，**只遍历本机 sink，绝不再 fanout**（防回环）

---

# 第 12 章 rendering/compat：SegmentType 与上游后处理协议

`rendering/compat/` 这个包是 Leto 为 Agentspark 这类「上游业务方」预留的**协议缝隙**。

## 12.1 三个 flag

`SegmentType` 每个枚举值携带三个布尔：

| flag | 含义 |
|-|-|
| `needPostProcess` | true → 经 `SegmentFragmentBuilder` 后处理后 push `FragmentPushEvent`；**false → 直接 push `SegmentResultPushEvent` 给 agentspark 自己处理** |
| `needSend` | 是否推送给客户端 |
| `needSave` | 是否保存到 SummaryResult |

## 12.2 枚举全表

| 枚举 | postProcess / send / save | 说明 |
|-|-|-|
| `TOKEN` | ✓ / ✓ / ✓ | token 级流式 |
| `TEXT` | ✓ / ✓ / ✓ | 普通文本 |
| `IMAGE` / `IMAGE_KEYWORD` / `ONLY_IMAGE` / `IMG_LIST` | ✓ / ✓ / ✗ | 图片类 |
| `COMPLEX_ARG_EMPTY` | ✗ / ✗ / ✗ | 复合参数为空 |
| `COMPLEX_ARG_HIT` | ✓ / ✓ / ✗ | 复合参数命中 |
| `COMPLEX_LIST` | ✗ / ✗ / ✗ | 列表开始标记 |
| `COMPLEX_LIST_END` | ✓ / ✓ / ✗ | 列表结束，后处理后推送 |
| `COMPLEX_LIST_INVALID` / `COMPLEX_LIST_NEW` | ✗✗✗ / ✓✓✗ |  |
| `THINK` | ✗ / ✗ / ✗ | 思考链，不推不存 |
| `ONLY_IMAGE_RECUR` / `NBTI_IMAGE` | ✗ / ✗ / ✗ |  |
| `ORIGINAL_VOICE` | ✓ / ✓ / ✓ | 原声段 |
| `TEXT_SOURCE` / `ANSWER_BOOK` / `HIGH_LIGHT` / `QUOTE` | ✓ / ✓ / ✗ |  |
| `IMAGE_SEARCH_WORDS` / `GOODS_SEARCH_IMAGE` / `PRODUCT_CARD` | ✓ / ✓ / ✗ |  |
| `GUIDE_QUESTION` | ✗ / ✗ / **✓** | 引导问题：只保存不推送，且走 `SegmentResultPushEvent` |
| **`NOTES`** | ✗ / ✗ / ✗ | `<notes>` 多笔记，**leto 不处理**，交给 agentspark |
| **`NOTES_SINGLE`** | ✗ / ✗ / ✗ | `<notes_single>` 单笔记 |
| **`INLINE_CITATION`** | ✗ / ✗ / ✗ | 文中溯源 `[text](url)`，`originSegment` 含原始 markdown |
| **`SENTENCE_CITATION`** | ✗ / ✗ / ✗ | 句粒度溯源 `[Nx:y-z](#)`，与 INLINE 物理隔离 |
| **`INSPIRATION_CITATION`** | ✗ / ✗ / ✗ | 灵感库引用 `[I{index}](#)`，前缀 I 无冒号 |
| **`ORIGINAL_QUOTE`** | ✗ / ✗ / ✗ | `<quote source="...">` 原声引用 |
| **`GOODS`** | ✗ / **✓** / ✗ | `<goods>` 商品组件，注意 **needSend=true** |
| **`SHORT_CONTENT`** | ✗ / ✗ / ✗ | `<short_content>` 短气泡 |
| **`FILE_TAG`** | ✗ / ✗ / ✗ | `<file_tag scene path>`，leto 不读文件，metadata 带 scene/file_path |
| `IMAGE_SEARCH_WORDS_NOT_HIT_CACHE` | ✗ / ✗ / ✗ |  |
| **`TASK_INFO`** | ✗ / ✗ / ✗ | `<taskInfo>` 通用任务标签，agentspark 按 taskAction 决定是否产卡 |

**规律**：所有 Agentspark 自研的业务组件类型，`needPostProcess` 都是 `false`——leto 只负责**识别标签边界**，语义解析和端侧协议生成全部交给上游。这是两个仓库的职责分界线。

## 12.3 SearchContext

`SegmentResultPushEvent.searchContext` 携带：

- `userId`
- `attributes`（`ConcurrentHashMap`，通过 `Param<V>` 泛型接口类型安全存取）
- `snapshotSupplier`：IdIndexSnapshot 懒加载入口
- `resolveIdIndexSnapshot()` 三步读：attributes → snapshotSupplier → `IdIndexSnapshot.empty()`
- 常量 `ID_INDEX_SNAPSHOT_KEY = "id_index_snapshot"`（对应 `ContextParams.ID_INDEX_SNAPSHOT`）

---

# 第 13 章 周边子系统：config/versioning、feature/、loop/chained

这三个包点点主链路都**没用或用得很浅**，但了解它们能看清 Leto 的完整设计意图。

## 13.1 config/versioning：多版本配置 + 维度路由

解决的问题：**同一个 agent，不同实验分桶要跑不同配置版本**。

```java
class ConfigVersion<T> {
    String moduleType;   // "llm" / "agent" / ...
    String versionId;    // "v2"
    T data;              // 如 LlmProperties
    String source;       // "yaml" | "apollo"
}

interface ConfigVersionRepository {
    <T> Optional<T> get(String moduleType, String versionId, Class<T> type);  // 不存在返 empty，不抛
    void registerModule(String moduleType, Map<String, T> versions);          // 静态注册
    void registerModule(String moduleType, ConfigProvider<T> provider);       // 订阅热更
    void refreshVersion(String module, String version, Object config);
    void onChange(Runnable listener);
}

// 消费方唯一入口
ConfigOverlays.effective(cfg, repo, moduleType, type, baseProvider);
//   cfg.versionRef(moduleType) 非空 && repo 命中 → overlay
//   否则 → baseProvider.get()（Apollo live view）
```

Apollo key 约定（`ConfigKeys`）：

```
arkai.leto.{module}                                       base properties
arkai.leto.runtime.agent-versions.{agentId}.{dim}         per-agent 维度变体
arkai.leto.a2a.agent-cards.{agentId}                      A2A agent card
arkai.leto.runtime.llm-endpoints                          LLM 端点
```

**维度路由**：

```java
class DimensionRouteSpec {
    String experimentFlag;              // 查外部实验平台（Racing）
    List<Candidate> candidates;         // 多候选 + JEXL3 boolean 条件，首个 true 胜出
    String condition;                   // @Deprecated 本地 JEXL3 求值
}
enum DimensionType { OVERALL, ASPECT }  // OVERALL 先应用（整体切版本），ASPECT 后应用（细粒度微调）

interface DimensionRouter {
    Map<String, String> resolveVersions(Map<String, DimensionRouteSpec> dims, RouteContext ctx);
}
```

批量处理所有维度：Racing flag **合并成单次网络请求**，JEXL3 条件本地求值。实现有 `ExperimentBackedVersionRouteClient`（对接 Racing）和 `NoopVersionRouteClient`。

> 点点没走这套，而是在 `DiandianChatService` 里自己实现了 racing flag 的批量拉取（见 14.1）。两者思路一致：**多实验合并单次 RPC**。

## 13.2 feature/：通用特征计算框架

注意**和 `router/feature/` 不是一回事**。这个包是「懒加载 + 依赖拓扑 + 结果缓存」的特征计算容器。

```java
public interface FeatureGroup {
    String name();                                            // null 时从 @FeatureGroupDef 解析
    default Set<String> dependencies() { return Set.of(); }   // 依赖的其它组
    Map<String, Object> compute(FeatureComputeContext context);
}

@FeatureGroupDef(name = "user_profile", dependencies = {"user_base"})
public class UserProfileFeatureGroup implements FeatureGroup { ... }

public interface FeatureStore {
    Map<String, Object> getGroup(String groupName);
    Object get(String groupName, String featureKey);
    <T> T get(String groupName, String featureKey, Class<T> type);
}
```

框架保证：**同一个 `FeatureStore` 内每个组最多算一次**，依赖组在被调用前已完成。`FeatureStore` 每请求一个实例（`FeatureStoreFactory` 创建），跨请求不共享。

`FeatureStoreInitNode`（order=50）把它以**瞬态属性**塞进 `FeatureContext`，后续 FeatureNode 用 `context.getAttribute("featureStore", FeatureStore.class)` 取。

## 13.3 loop/chained：多 Agent 链式编排

`ChainedAgentLoop`（`loopType = "chained"`）是对 `DefaultAgentLoop` 的**编排级扩展**，用于 arksearchai 这类需要「plan agent → toolCall agent → summary agent」串联的场景。

`ChainedAgentDef` 两种形态：

- **leaf agent**：`agentSequence` 为空，按 `[自己]` 跑一遍
- **composite agent**：`agentSequence` 非空，本身不调 LLM，串接调用 leaf agents

四种退出：

1. `ExitWithLabel` —— `exitBoundary`（SpEL）或 `ExitBoundaryResolver`（Java SPI）返回非空 label
2. `Completed` —— sequence 走完且最后一个 agent `shouldContinueSelf=false`
3. `Cancelled` —— cancelToken 或 `ctx.isInterrupted()`
4. `Failed` —— `totalSteps >= maxTotalSteps`

`NextAgentDecision` 四态：`Stay`（同 agent 再来一轮）/ `JumpTo(agentId)` / `Advance` / `Exit`。**Java SPI 优先于 SpEL**。

12 个 SPI 插槽（都可为 null，有框架默认）：

| SPI | 职责 |
|-|-|
| `ModelInvoker` | 模型调用执行器（同步/流式） |
| `MessageAssembler` | 装配 messages（三级链：业务 → `ContextEngineMessageAssembler` → system+user 兜底） |
| `ModelEndpointResolver` | 决定端点 |
| `ModelOutcomeParser` | 解析输出为 `ModelOutcome` |
| `ModelCallGate` | Stage 2.9：决定是否跳过 model 调用 |
| `ExitBoundaryResolver` / `NextAgentResolver` | 退出 / 路由决策 |
| `AgentFallbackHandler` | agent turn 异常容错 |
| `PrefetchOperator` | 同步 / 异步 prefetch（异步可与 model+tools 并行，`asyncPrefetchJoinTiming` 控制收口时机） |
| `PreModelStep` / `PostModelStep` | model 前 / tools 后副作用 |
| `PostAuditGate` | 后置审计，结果写 `ctx.attrs()` 供 `exitBoundary` SpEL 读 |

跨 agent 数据传递统一走 `ChainedLoopContext.attrs()` Map，**不引入每个 agent 的专属类型**。

`engine/impl/graph/` 是另一套 DAG 编排（`DefaultOperatorGraph` + Kahn 拓扑排序 + `JexlRoutingOperator`），节点类型 `MAP` / `FLAT_MAP` / `FAN_OUT` / `FAN_IN` / `SPLIT` / `MERGE` / `REDUCE` / `STREAM_MAP`，支持 JSON DSL（`ArkJsonGraphBuilder`）。Agentspark 的 `agentspark-graph` 模块用的就是这套，通过 `AgentGraphServiceImpl` → `GraphInvokeService.invoke()` 驱动轻量 LLM 小任务（评论意图、分享文案、会话切分），与点点主链路完全解耦。

---

# 第 14 章 Agentspark 如何接入 Leto

## 14.1 入口与实验分桶

```
DiandianController.chat()                    :107
  ├─ assertCanUseXhsUid(user_id)             :111   corp uid header 鉴权
  └─ DiandianMessageContextFactory           :47/78/118   HTTP/长连归一
        ↓
DiandianChatService.chat()                   :125
  ├─ resolveRacingFlags(...)                 :137 → :199
  ├─ RacingFlagDefaults.withWebFortuneDefault(...)
  ├─ logRacingExperimentHits()               :150
  ├─ logContextCompactionDecision()          :182
  ├─ 参数校验 :160 / 输入安审 :168
  ├─ DiandianInvocationFactory               :47（rawInputs 原样透传 :51）
  │    └─ invocation.withRacingFlags(flags)  :392
  └─ dispatch → DiandianAgentPort.stream()   :406
```

### resolveRacingFlags（`:199`）—— 一次 RPC 拉全部分桶

```
1. DiandianExperimentFlagCatalog.allFlags(properties.getRacingExperiments())
     汇总两类来源：
       ① 声明式实验：Apollo racingExperiments（RacingFlagConfig：flag / hitValue / appOs）
       ② 压缩固定目录：catalog 里硬编码的
          max_turns / ctx_compaction_z / ctx_compaction_z2 / ctx_compaction_continuation /
          summary_trigger_turns / tool_full_turns / skeleton_turns / tool_keep_stub …
2. removeDisabledFixedExperimentFlags()   剔除开关关闭的固定实验
3. 集合为空 → 直接 return Map.of()，不发 RPC
4. agentVersionExperimentPort.resolveRacingFlags(appOs, appVersion, userId, flags)
     一次 RPC 批量取回所有分桶值
5. try/catch 包裹：racing 平台异常 → ERROR 日志 + 返回空 map（fail-open）
     所有实验回落 Apollo / 代码基线，不阻断主链路
```

> **「固定目录」存在的意义**：即使运营忘了在 Apollo `racingExperiments` 里声明某个压缩实验，catalog 的硬编码列表也会把它带进查询集合，实验仍能按 userId 正常分桶。这是一层**防配置遗漏**的兜底。

### RacingFlagDefaults.withWebFortuneDefault（`RacingFlagDefaults.java:27`）—— Web 入口兜底

紧跟其后，**三个条件全部成立**时补 `agent_fortune_dots = "1"`：

1. racing 返回的 flags 里 `agent_fortune_dots` 为空（`:46` —— **已有显式分桶就不覆盖**）
2. 是 web 请求（`:71-75`）：`appOs` 或 `clientPlatform` 归一化后 == `web`/`browser`，**或**`normalizedEntry == HTTP`
3. Apollo `racingExperiments` 里**确实声明了**该 flag 且 `hitValue == "1"`（`:55` —— **实验下线后不再误补**）

返回**新的**`LinkedHashMap`，原 map 不被修改。

> **为什么需要**：racing 平台按 App 的 os/version 分桶，网页入口拿不到有效 `appOs`，平台不会返回 web 分桶 → 运势工具在网页上恒被禁用。这行给 web 入口一个默认命中值。  
> 条件 3 是关键约束：**不硬编码业务开关，只在 Apollo 已声明该实验时才补**，实验下线自动失效。

## 14.2 core → runtime 的边界

```
DiandianChatService（agentspark-core，禁止 import leto）
   ↓ DiandianAgentPort.stream()      ← port 接口
ArkDiandianAgentAdapter（agentspark-runtime）    :128
   ↓ 起虚拟线程                                   :200 / :212
   ↓ DiandianArkRequestFactory                    :32 / :46
   ↓ router.execute(featureRequest, sink, hook)   :331
```

**`DiandianFeatureRequest`**（`:19`）继承 leto `FeatureRequest`，做了一个反直觉的设计：

```java
// 构造时 parts 传【空列表】，真实内容放进 rawInputs
super(..., /* parts */ List.of(), ...);
this.rawInputs = rawInputs;   // List<ContentItem>，全是 ID
```

等 order=50 的 `ContentResolveFeatureNode` 批量 RPC 解析完，再 `setParts()` 回填。

五个扩展字段：`rawInputs`（原始 ID 输入）/ `agentVersionFlag` / `appOs` / `racingFlags`（全部实验分桶）/ `gsbExecution`（GSB 策略覆盖）。

## 14.3 十个 FeatureNode

**注册规则（硬约束）**：业务 FeatureNode **禁止 `@Component` 自动扫描**，统一在 `DiandianArkFeatureConfiguration`（`:224` / `:244`）以 `diandian*FeatureNode` 显式 `@Bean` 注册；`diandianFeaturePipelineResolver` 必须用 `@Qualifier`**逐个列举**。

> **为什么不能 by-type 自动收集**：多个 runtime 共存时，其它 runtime 注册的 FeatureNode Bean 会自动飘进点点的 pipeline，污染执行链。

新增节点三步：① `@Bean diandianXxxFeatureNode(...)`；② 在 resolver 形参表加 `@Qualifier("diandianXxxFeatureNode") FeatureNode xxx`；③ 加进 `List.of(...)`。

| order | 节点 | 职责 | 为什么在这个位置 |
|-|-|-|-|
| **50** | `ContentResolveFeatureNode`（`:77`） | 并行 RPC 解析 11 种 `ContentItem` → `ContentPart`；图片预算（P0/P1/P2，token 超限丢低优图） | 几十次 RPC 必须并行 + 可降级。放 Controller 会阻塞 HTTP 线程，放 AgentLoop 拿不到 pipeline 上下文 |
| **70** | `CommentEntrySceneInjectFeatureNode`（`:34`） | metadata `anchor_type=comment_*` 时，在用户输入**前** prepend 笔记 + 评论上下文 | LLM 完全不知道用户在说哪条评论，必须在 Session 加载前注入 |
| 100 | *leto*`SessionLoadFeatureNode` | 加载 Session 事件日志 | — |
| **120** | `LegacyHistoryRehydrateFeatureNode`（`:25`） | 从 `ChatMessageRepository` 回灌旧版历史（Apollo 白名单控制） | 迁移期过渡：老用户历史不在 leto Session 里 |
| **160** | `InitiatorContextFeatureNode`（`:55`） | 并发拉场景记忆 / LDS 画像 / geo，渲染追加到 **user message** | 产出是 user message，与 350 改 system prompt 分开：位置不同、KV cache 命中率不同 |
| **200** | `DiandianAgentResolveFeatureNode`（`:39`） | 解析 AgentDef；新会话调 `sessionManager.create()`，写 ownerUserId 和 RPC Context | 替换 leto 默认 AgentResolve，因为要写自定义字段 |
| **250** | `IdIndexInjectFeatureNode`（`:50`） | 实体登记进 snapshot；TextPart 里的真实 ID 改写成短 ID（N1/U1） | **必须晚于 200**：新会话的 Session 在 200 才创建，早于 200 分配不了短 ID |
| 300 | *leto*`ParamInitFeatureNode` | `RuntimeContext.params()` 初始化 | — |
| **350** | `DiandianSystemPromptFeatureNode`（`:42`） | core memory / LDS 性别年龄城市替换 **system prompt** 末尾占位符 | 产出是 system prompt |
| 400 | *leto*`ResumeEvalFeatureNode` | 断点续跑评估 | — |

### ContentResolve 的四条红线

1. **走对应 tool，不绕过**：Note/User/Comment/Product Resolver 必须通过 `note_read` / `user_read` / `comment_read` / `product_read` 解析，与 LLM 主动 tool_call **走完全相同的链路**——否则预解析和主动调用会出现结果不一致。
2. **批量 / 并行**：批量 RPC 走批接口；只能单调用的 tool 用 `ParallelToolInvoker` 并发，配合 `TaskHelper.wrapCallable(...)` 透传 CAT/MDC/Scope/RpcContext。
3. **去重**：同一 ID 多次出现共享一次 RPC。
4. **降级**：RPC 异常 → ERROR 日志 + 占位 TextPart，**不抛出、不打断主链**。

另外：Resolver 必须调 `ctx.registerEntity(entityType, naturalId, item)` 登记到 `ResolveContext.entitySink`，否则 order=250 不会给这个 ID 分配短 ID。

### IdIndex 长短 ID 中间件

- 短 ID（`N1`/`U1`）是**本轮上下文的临时 ID**，不是稳定 noteId。24 字符 hex ≈ 12 token，短 ID ≈ 1 token。
- 溯源/引用要拿真实笔记 ID 时：**先走 `IdIndexSnapshot` 短 ID 反查**，找不到才从 `TextSourceToolCallResult.result` 解析。
- `IdIndexSnapshotPruningCompactor` 是借 leto Compactor SPI 做跨轮剪枝的**副作用 hook，不裁 messages**；`MIN_ENTITY_COUNT=50` 硬编码在类内，改要发版。
- `IndexedEntity` 的业务字段一律读 `displayFields`，**不能依赖 `IndexedEntity.item`**（item 已置 null 以治理大 Key）。

## 14.4 工具池注册

`@AgentSparkTool` 注解只做两件事：① 让 Spring 自动 wire 依赖；② `group()` 供 leto `ToolGroupConfig` 切组。**它不决定这个工具是否进 LLM 工具池。**

点点工具池在 `DiandianArkToolConfiguration#diandianTools`**显式列举全部 56 个工具**：

```
note(5) / user(3) / comment(3) / ecom(2) / interaction(2) / poi(3) /
toolsearch(2) / memory(1) / task(8) / search(8) / utility(14) / fortune(4) / hotevent(1)
```

新增工具三步（缺一不可）：

1. tool 类加 `@AgentSparkTool(group = "xxx")`
2. `diandianTools`**形参表**加一行 `XxxTool xxxTool`
3. 方法体 `List.of(...)`**末尾**加 `xxxTool`

启动日志 `[DianDianAgent] registered N diandianTools` 自检数量。

三处消费方（`diandianToolRegistrar` / `DiandianAgentDefFactory` / `diandianContextRendererRegistry`）全部通过 `@Qualifier("diandianTools") List<Tool>` 订阅，**禁止 by-type 自动收集**（理由同 FeatureNode）。

**IdAwareToolWrapper**（registrar `:239`）：任何 `ParamSchema` 填了 `entityType` 的工具会自动套一层 wrapper——LLM 传 `N1` 进来，wrapper 自动反查回真实 noteId 再调 RPC。这就是 leto `ParamSchema.entityType` 字段的落地方式。

## 14.5 ProfileBackedArkLlmClient

点点没改 `DefaultAgentLoop` 一行，但换掉了 `LlmClient`。`DiandianArkRuntimeConfiguration:144` 注入自定义 `llmClient` / `toolRegistry` / `contextEngine`（图片预算）/ `rendererRegistry`（IdIndex 输出改写）/ `toolResultPreRenderProcessors`。

`ProfileBackedArkLlmClient` 的八项能力：

| 能力 | 位置 |
|-|-|
| Profile 路由（按 Apollo JSON 选模型/endpoint/超时，热更新） | `:1163 resolveRequest` / `:1280 delegate` |
| reasoning 归一（Kimi/DeepSeek `reasoning_content` → 统一内联 `<think>`） | `:1585` |
| 流式重试（首 chunk 超时/空流，退避，最多 1 次外层重试） | `:440 withStreamRetry` |
| 高水位续写（上下文接近上限时插入引导语，**不写进输出流**） | `:79-81` |
| toolCall 配对修复（历史 `assistant.tool_calls` 与 tool result 不配对时清理） | `:1284` |
| 图片 Base64 转换（按 scope 决定 URL 直传或 base64） | `:88` |
| 错误指纹脱敏（URL/UUID/长 hex/数字替换，**防 metric 标签基数爆炸**） | `:65-71` |
| Trace 捕获（喂给 ThinkingTrace 持久化） | `:87` |

**think 状态机在 Agentspark 侧，不在 LLM client 侧**：`ThinkingExtractor`（`:57`/`:83`）处理跨 chunk 的半截 `<think>` 标签，上限 256KB；`ThinkingEventTransformer`（`:133`/`:179`）分派 Open/Delta/Close 事件；`:217 stripThink` 做终态剥离（`ArkDiandianAgentAdapter:455` 调用）。

## 14.6 点点用了 / 没用 leto 的哪些能力

**用了**：多轮循环 + maxTurns、ToolCallHistory 死循环检测、eager dispatch、413 溢出重试、cancel 检查点、OutputGuard、FeaturePipeline、Session 事件溯源、分布式执行权、插话 drain、ContextEngine 压缩、ToolGroup、IdIndex `entityType`。

**没用**：HITL 审批、handoff、子 agent、checkpoint 恢复、Skill 路由 / ephemeral skill、Graph/DAG 编排（`engine/impl/graph` 只被 `agentspark-graph` 模块单独用）、`loop/chained`、MCP。

---

# 第 15 章 Agentspark 的输出侧：Segment → FragmentDraft → BFF → 端

## 15.1 四层数据形态

一段内容从 LLM 出来到端上渲染，会换四次「衣服」：

| 层 | 数据形态 | 归属 | 关注点 |
|-|-|-|-|
| 1 | `SegmentResult` / `SegmentType` | **leto** | 标签边界识别 |
| 2 | `FragmentDraft`（sealed） | **agentspark-core** | 中立业务草稿，不含端侧展示结构 |
| 3 | raw payload + `content_type` | **agentspark-core**（落库） | 可被 BFF 二次加工的业务原始数据 |
| 4 | 端侧 `element` / `elements` / `spark` / `smart_call` | **agentspark-bff** | 端侧渲染协议 |

> **AGENTS.md 的硬约束**：新增一个 `SegmentType` 必须按「runtime 适配 → core 中立草稿 → raw payload 落库 → BFF 出端侧」分层推进，**不允许在单层里跨过整条链路**。

## 15.2 事件映射链

```
leto PushEvent
  ├─ FragmentPushEvent      → DiandianArkEventMapper（SegmentPushEventConverter）→ Markdown draft
  └─ SegmentResultPushEvent → DiandianArkEventMapper:144
                              → DefaultSegmentResultSpecialProcessor
                              → SegmentResultProcessingRouter:40   （legacy / agentspark 双路）
                              → AgentsparkSegmentResultProcessor:71/184
                              → 各 SegmentResultHandler
      ↓
FragmentDraft（sealed，content_type 定义在这里）
   Markdown=7 / QueryStatus=10 / Notes=11 / NotesSingle=12 /
   GuideQuestion=13 / SubscriptionTitle=19 / ShortContent=101 …
      ↓
FragmentDraftPayloadProjector:46      draft → raw payload + content_type
      ↓
DataFragmentStreamAccumulator:165（实时 drain）/ :477（终态落库 payload）
      ↓
ProtocolTranslatorChain:103           按 @Order 命中第一个 translator
   ↳ injectMessageType:145            注入端侧字段
      ↓
端侧
```

## 15.3 DB content_type vs BFF 出口 content_type

**这是两套编号，不能混**：

| 落库 raw `content_type` | 语义 |
|-|-|
| 7 | Markdown 单体 |
| 8 | 混排容器（`elements[]`，每个 child 保留自己的 raw content_type） |
| 10 | QueryStatus |
| 11 / 12 | 多笔笔记 / 单笔笔记 |
| 13 | GUIDE_QUESTION |
| 19 | 订阅标题 |
| 101 | ShortContent |
| 1/2/3/14/15/16/102/105/107… | 其它 raw 路由标记 |

| BFF **出口**`content_type` | 端侧形态 |
|-|-|
| **4** | 用户输入 / inputs |
| **5** | `CREATE_SURFACE` smart_calls |
| **7** | 单个 element（markdown / spark / image） |
| **8** | 多 element 容器 |

**BFF 常规出端侧只有这四类**。raw 类型只是路由标记，不直接出端侧。QueryStatus/Notes/GuideQuestion 在 DB 保留独立 raw 类型，到 BFF 后通常变成 `content_type=7 + element_type=spark`。

新增 translator：实现 `ProtocolTranslator` 接口，用 `@Order` 控优先级，**不改 chain 本身**。查当前优先级：

```bash
rg '@Order\(' agentspark-bff/src/main/java
```

## 15.4 SSE 写出与落库

```
SseFluxBridge:63                唯一的 Flux.create（Reactor 边界），sink::next 推帧
   ↑
DiandianSseEventWriter
  ├─ wrap()                     :167/:185   存占位消息 + 返回 EventListener
  ├─ onEvent → processOne       :190/:191   → sseSink.accept
  │    └─ DiandianSseEventProcessor.processOne:70   sealed 模式匹配分派
  │         ├─ 累积安审 :711/:715
  │         ├─ 终态安审 :241
  │         └─ 实时聚合 :464
  └─ endAndSave                 :816
       └─ DiandianSseAgentMessageSaver
            ├─ payloadOrMarkdown        :176/:179
            ├─ saveMainAgentMessage     :247
            └─ 触发 MsgEntityIndex + ThinkingTrace   :252/:253
```

长连链路的对应关系：

```
LinkUpMessageHandler → LonglinkMessageContextFactory → LinkMessageProcessor
  → DiandianChatService → LonglinkAgentEventBridge
       → LonglinkEventRouter → LonglinkDraftDispatcher → BFF → 端侧下行帧
```

长连侧改点分层：

| 想改什么 | 改哪 |
|-|-|
| 新增 `DiandianStreamEvent` 处理 | `event/*EventHandler` |
| 新增 `FragmentDraft` 下发形态 | `draft/*DraftHandler` |
| 端侧 envelope / seq / start / end / tool / audit 帧 | `LonglinkDownlinkSender` |
| 终态落库 / placeholder / MsgEntityIndex / ThinkingTrace | `LonglinkMessagePersistenceService` |
| 安审 | `LonglinkOutputAuditHandler` |

## 15.5 输出安审

`OutputAuditEventTransformer` 挂在 `ArkDiandianAgentAdapter.stream()` 的管道上，**HTTP 和长连共享同一条链路**，`fail-open`（安审 RPC 异常时放行）。

| 模式 | Apollo 开关 | 触发时机 |
|-|-|-|
| 累积审核 | `audit.output-accumulate-enabled` | `content_delta` 累积到阈值，**中断 LLM** |
| 终态审核 | `audit.output-final-enabled` | `stream_complete` 事件，仅标记会话 |

## 15.6 DQA 来源 vs ThinkingTrace

两个概念必须分清：

|  | `MsgEntityIndex` | `ThinkingTrace` |
|-|-|-|
| 是什么 | 当前 assistant `msg_id` 的**来源 ID 权威索引** | 同一 `msg_id` 的全思考链路 + 工具调用过程快照 |
| 决定什么 | DQA 来源列表有哪些 note/comment/web/user 及顺序 | thinking 展示 / 排障 / **旧来源兜底** |
| 能不能反向决定 sources | **是**（主路径） | **否**。`MsgEntityIndex` 一旦命中，ThinkingTrace 不能追加或重排 sources |

**为什么要拆**：来源卡片需要稳定的业务 ID 集合，而 tool trace 是过程数据。把 `search_request` 的 tool result 当主来源，来源列表会受工具过程、失败重试、pending tool、trace 写入时机影响。

**写入时机（两者对齐）**：都在**主回答消息保存成功之后**，通过 `*PersistencePort.persistForMessage(sessionId, msgId)` 触发，**不在单个 tool completed 阶段写**。

> **为什么不能在 tool completed 写**：同一个 `msg_id` 会经历多次工具完成、取消、pending、stream end 兜底，按过程事件写最终 trace 会产生多次覆盖和半成品状态。主消息保存成功是本轮 assistant `msg_id` 对外可查询的稳定边界。

采集链路：`SegmentEntityIndexCollector` 在本轮 stream 内观察每个 PushEvent，优先从 `ToolCallResult.idIndexSnapshot` 和 `SegmentResultPushEvent.searchContext[ID_INDEX_SNAPSHOT]` 投影，再用 `FragmentPushEvent` / `SegmentResult` payload 扫描兜底，按 `segmentId` 合并。**任一事件为空都不能把该 segmentId 标成完成。**

这两类 RedKV 数据**不设过期时间**。

## 15.7 三条容易漏的下游约束

### ① 新增非纯文本 payload 必须补文本摘要

历史列表、消息盒子 latest notice、push、分享文案**不会渲染端侧组件**，只能靠服务端把 payload 降级成纯文本。

新增 `ContentType` / `FragmentDraft` / BFF Translator / RN spark 组件时，必须同步补 `MessageContentExtractorChain` / `ChatMessageContentExtractor` / `AgentMessagePayloadSummary`。

**至少补两类测试**：

1. 组件 payload 本身能抽出摘要
2. 删除最后一轮消息后，`ChatIntentHistoryService` 刷新 latest notice 能回退到该组件摘要

若该 payload 可能嵌在 `ELEMENTS(8)` / `ASK_WRAPPER(200)` 容器里，还要补嵌套摘要测试。

### ② `base_deeplink` 只给主回答

- 实时 SSE / 长连：只在 `FragmentMessageRole.MAIN` 前补
- 历史回放：只对 DB `content_type = 7/8` 补
- ShortContent、GUIDE_QUESTION 这类独立逻辑消息**不生成**

**为什么**：`base_deeplink` 归属于本轮主回答的 assistant `msg_id`。前置短气泡和后置追问有各自独立的 `msg_id`，给它们补 deeplink 会让端侧把独立消息误当主回答入口。

不要只凭 `sender_id == diandian-agent` 判断。

### ③ `message_params` / `engage_bar_functions`

- BFF 统一为 `ai_answer` / `ai_answer_single` / `ai_subscription` 三类回答型 payload 补顶层 `message_params`
- `engage_bar_functions` 由 BFF 根据最终 `message_type` 生成，**不要求 DB 保存**，不根据 `message_type` 默认开启
- 塔罗 RN 抽牌卡由 payload 模块名强制关闭
- `support_regenerate=false` 是 core → BFF 的内部信号，只移除 `regenerate`
- DB / commonParams 缺失时**不补**`message_params`

---

# 第 16 章 红线速查与排障索引

## 16.1 Leto 层不可违背的约束

| # | 约束 | 违反后果 |
|-|-|-|
| 1 | `RouterLayer → FeaturePipeline → AgentLoop` 顺序不可改 | 前置不变量失效，崩溃恢复/插话语义全废 |
| 2 | NEW_EXECUTION 必须 **acquire 先于 commit** | TOCTOU → 双 loop 双写 |
| 3 | STEER 必须 **先写 QueuedUserMessage 后 cancel** | drain 重读抢先 → 插话丢失 |
| 4 | `SessionExecutor` finally 必须 **放锁前删 token** | ABA → 误删接管者的新 token |
| 5 | Fencing 作用于**整个持权区间**，不是单轮 | drain 用新 token 重开 → 与接管者双写 |
| 6 | `close()` 必须 **先 cancel 后停 scheduler** | 在途 loop 无锁保护仍在写 |
| 7 | `InterruptSignalStore.poll` 必须**单次读** | 漏判 steer → 插话降级成普通取消 |
| 8 | `WorkspaceEvent.position`**set-once** | 二次赋值抛异常 |
| 9 | 压缩路径 token 估算必须用 `estimateMessage(m, modelHint)` | `assistant(tool_use)` 被估成 0 token |
| 10 | `appendCompactionSummary`**先 append 后推热缓存** | 跨 pod 读不到摘要 |
| 11 | `TruncatingCompactor`**绝不用于 hard 兜底链** | 不产 meta/summary → 破 no-gap |
| 12 | `compactionMeta == null` 是 noop 信号 | hard 档必须进兜底链 |
| 13 | `assemble()` 入口**原子读一次 prevMeta**，全程共用 | 撕裂视图 |
| 14 | `ContextLayer` / `ContextRenderer` 必须**无状态** | 单例并发下串数据 |
| 15 | 只实现旧 `shouldCompact` 的 trigger 产不出 ASYNC 档 | 异步压缩静默不生效 |
| 16 | 并行 fan-out 工具必须 `ToolContext.copy()` | skill 归因串写 |

## 16.2 Agentspark 层不可违背的约束

| # | 约束 |
|-|-|
| 1 | `agentspark-core` 禁止 import `com.red.arkai.leto.*` / `org.springframework.web.*` / RPC SDK / runtime / infrastructure / start；core 调 runtime 走 port |
| 2 | leto `@Bean` 装配只放 `agentspark-runtime` |
| 3 | `agentspark-bff` 不 import core/common/infrastructure（唯一允许的仓内依赖是 `agentspark-experiment-api`） |
| 4 | FeatureNode 禁止 `@Component`，统一在 `DiandianArkFeatureConfiguration` 显式 `@Bean` + `@Qualifier` 逐个列举 |
| 5 | `IdIndexInjectFeatureNode`(250) **必须晚于**`DiandianAgentResolveFeatureNode`(200) |
| 6 | `diandianTools` 显式列举，禁止 by-type 自动收集 |
| 7 | ContentResolve 必须走对应 tool、批量并行、去重、降级不打断 |
| 8 | 溯源取真实笔记 ID：先 `IdIndexSnapshot` 短 ID 反查，找不到才解析 tool result |
| 9 | `IndexedEntity` 业务字段读 `displayFields`，不读 `item`（已置 null） |
| 10 | `MsgEntityIndex` 命中后 `ThinkingTrace` 不能追加/重排 sources |
| 11 | 两者都在主消息保存成功后 `persistForMessage` 触发 |
| 12 | 新增非纯文本 payload 必须同步补文本摘要 + 两类测试 |
| 13 | `base_deeplink` 只在 MAIN / DB content_type=7,8 时补 |
| 14 | 字符串工具用 `org.apache.commons.lang3.StringUtils`，禁 `org.springframework.util.StringUtils` |
| 15 | 进 LLM 的字符串截断必须用 `SurrogateSafeStrings.head/tail/substring`，禁裸 `String.substring`（会劈裂 emoji surrogate pair） |
| 16 | 提交 `ExecutorService` 的任务必须 `TaskHelper.wrapCallable/wrapRunnable` 包装 |
| 17 | 跨线程缓存必须有上限和淘汰策略（优先 Caffeine `expireAfterAccess + maximumSize`） |
| 18 | 流式累积 buffer 必须设硬上限 |
| 19 | 主链路 RedKV Jedis 只能通过 `agentsparkRedkvJedis` 注入；runtime 模块用裸字符串 `@Qualifier("agentsparkRedkvJedis")` |
| 20 | 协议模型（`ContentItem`/`ContentType`/`Payloads`）在**独立**`agentspark-protocol` 仓库，先发版再 bump 依赖 |

## 16.3 排障：症状 → 先看哪

| 症状 | 排查顺序 |
|-|-|
| 请求返回 busy | `commitIngressByAction` 的 CONFLICT 分支 → Redis `arkai:running:{sessionId}` 是否有僵尸锁 → `JedisAuthorityStore` 的 renew 日志 |
| 插话丢失 | `commitSteerInterrupt` 的写入顺序 → `drainQueuedMessages` 的 `restoreFresh` → `fencedSessions` 是否被置位 |
| 同一 session 出现两份回答 | `ExecutionAuthorityStore.renew` 是否在 redkv（非原子）路径 → fencing 是否覆盖整个 drain 循环 |
| 上下文超长报 413 | `ContextEvent.CompactionExhausted` / `CompactionFallbackEngaged` 埋点 → `BudgetThresholdTrigger` 的 ratio → estimator 是否用了 `estimateMessage` |
| 压缩没触发 | trigger 是否只实现了旧 `shouldCompact`（产不出 ASYNC）→ `bandAware()` |
| 摘要丢失 / 历史断层 | `appendCompactionSummary` 写序 → `compressedUntilPosition` 与 `protectFirstN` 的重叠 |
| 工具反复调同一个 | `ToolCallHistory.detect` 的 argsHash（是否被 `ToolArgCoercer` 归一）→ `consecutiveLoopWarnings` 计数 |
| 工具结果没渲染成卡片 | `SegmentType.needPostProcess` → `SegmentResultProcessingRouter` 双路 → `FragmentDraft` 是否有对应 handler → BFF translator `@Order` |
| 历史列表 latest notice 为空 | `MessageContentExtractorChain` 是否覆盖了新 payload |
| DQA 来源缺失 | `MsgEntityIndex` 是否为空（空才会退回 ThinkingTrace）→ `SegmentEntityIndexCollector` 是否收到 `idIndexSnapshot` |
| 短 ID 全链路失效 | `IdIndexInjectFeatureNode` order 是否 < 200 → Resolver 是否调了 `ctx.registerEntity` |
| 端侧渲染错位 | DB raw `content_type` vs BFF 出口 content_type 是否搞混 |
| emoji 变成 `\uD83D` | 某处用了裸 `String.substring` |
| 子线程丢 traceId | 少了 `TaskHelper.wrapCallable/wrapRunnable` |

## 16.4 源码查阅速查

```bash
# leto 源码（不要凭记忆、不要反编译 .class，版本间签名变化频繁）
unzip -p ~/.m2/repository/com/red/arkai/leto/.../*-sources.jar 'com/red/arkai/leto/路径/类名.java'
# 或整包解压后 rg
unzip -o ~/.m2/repository/com/red/arkai/leto/*/*/*-sources.jar -d /tmp/leto-src

# Apollo key 现查（不维护全表，会漂移）
rg 'diandian\.agent\.' agentspark-core/src
rg '@Value|@ApolloJsonValue' agentspark-runtime/src
rg '^\s*[a-z_.-]+:' agentspark-start/src/main/resources/application-sit.yml

# BFF translator 优先级
rg '@Order\(' agentspark-bff/src/main/java

# 压缩 / content_type 链路
rg 'content_type|class .*Translator|Payloads\.' agentspark-core agentspark-bff
```

关键 Apollo 开关：

| key | 作用 |
|-|-|
| `diandian.agent.http-sse-debug-enabled` | HTTP/SSE 调试入口（sit=true，prod=false） |
| `diandian.agent.session-debug-enabled` | session 调试接口 |
| `diandian.agent.llm.config` | LLM profile（JSON，`@ApolloJsonValue`） |
| `agentspark.bff.translator-chain.enabled` | BFF 翻译链总开关 |
| `diandian.agent.context-compaction.tiered.*` | 分层压缩参数 |
| `audit.output-accumulate-enabled` / `audit.output-final-enabled` | 输出安审两种模式 |

## 16.5 构建与运行

```bash
# 必须 JDK 21（Mockito 5.7 的 byte-buddy 在 22+ 无法 instrument）
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

mvn compile -pl agentspark-core,agentspark-infrastructure,agentspark-tool,agentspark-runtime,agentspark-start -am
mvn test -pl agentspark-bff -Dtest=ProtocolTranslatorChainTest
mvn package -DskipTests
```

本地启动以 IDE 跑 `.run/Agentspark Local.run.xml`（profile=`sit`）为准。命令行启动必须带全套 `--add-opens`，**缺一个就会在 leto 反射路径炸 `InaccessibleObjectException`**（编译期看不出来）。改任何 JVM 参数时以该文件的 `VM_PARAMETERS` 为基准同步。

> 注：本地冷启动实际上跑不通（网络/Apollo 视图不完整），团队工作流是 push 分支 → 云效发布到 sit pod → 访问 sit devops 域名联调。

---

# 附：延伸阅读

| 文档 | 内容 |
|-|-|
| `AGENTS.md` | 仓库级硬约束（优先级高于本文档） |
| 根 / 各模块 `packages.md` | 模块地图与局部规则 |
| `agentspark-arch.html` | 架构 HTML 描述页，适合建立全局上下文 |
| `docs/diandian-输入预解析扩展指南.md` | 新增 ID 类输入的完整流程 |
| `docs/tool-idindex-长短ID双向中间件.md` | IdIndex 细节 |
| `docs/segment-result-bff-ai-coding-prompt.md` | SegmentResult → BFF 扩展 |
| `docs/agentspark-新业务runtime接入指南.md` | 接入一个新 runtime |
| `docs/agentspark-bff-协议映射.md` | BFF 协议映射表 |

**文档优先级**：当前源码事实 > `AGENTS.md` > 根 `packages.md` > 模块 `packages.md` > `README.md` / `docs/`（含本文档）。
