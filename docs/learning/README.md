# SeekFlux 学习路线与实现日志

这组文档既是开发日志，也是从零理解搜索推荐系统的学习材料。它不重复
[`SeekFlux.md`](../../SeekFlux.md) 的完整目标架构，而是回答三个更具体的问题：

1. 当前为什么采用这套工程框架；
2. 每个部分负责什么，应该按什么顺序实现；
3. 一段代码完成后，如何通过文档、测试和练习真正学会它。

## 阅读入口

- [00：架构选择与实现路线](00-architecture-and-roadmap.md)：先建立系统全貌，了解模块职责、依赖方向和纵向交付顺序。
- [Phase 0：工程基线](phase-00-engineering-baseline.md)：当前已经完成的目录、构建、契约和本地基础设施，以及这些工作背后的知识点。
- [Step 1：内容登记与画像发布](step-01-content-pipeline.md)：沿完整纵向切片学习领域状态机、R2DBC、事务 Outbox、Kafka 和幂等 Worker。
- [阶段学习文档模板](template.md)：以后每完成一个可运行部分时使用。

## 学习日志

| 阶段 | 可观察的用户价值 | 主要模块 | 状态 | 学习文档 |
| --- | --- | --- | --- | --- |
| Step 0（Phase 0 工程基线） | 工程可校验，基础设施可启动，边界可定位 | 根 Maven、目录骨架、契约、Deploy | 已完成 | [工程基线](phase-00-engineering-baseline.md) |
| Step 1 | 内容能够登记、异步处理并发布基础画像 | Content、Persistence、Messaging、Worker | 已完成 | [内容登记与画像发布](step-01-content-pipeline.md) |
| Step 2 | 用户能通过关键词搜索内容 | Search、Retrieval、Online Server | 待实现 | 实现时新增 |
| Step 3 | 用户能获得热门、相似和兴趣 Feed | Recommendation、Ranking、UserInterest | 待实现 | 实现时新增 |
| Step 4 | 曝光、播放和互动形成反馈闭环 | Interaction、Messaging、Persistence | 待实现 | 实现时新增 |
| Step 5 | 行为在分钟级更新在线特征和短期兴趣 | Realtime Features、Feature、Redis | 待实现 | 实现时新增 |
| Step 6 | 可训练、评测、发布并灰度排序模型 | Training、Model Serving、Experiment | 待实现 | 实现时新增 |
| Step 7 | 系统具备治理、降级、观测和端到端验收能力 | Moderation、Observability、Evals | 待实现 | 实现时新增 |

这里的 Step 是开发切片，不改变主架构文档中按数据规模定义的 Phase 0～3。
开发切片用于保证每一步都能运行、验证和学习，而不是同时铺开九个 Context 后到最后才联调。

## 文档与代码同步规则

以后一个开发切片只有同时满足下面条件才算完成：

- 功能代码、自动化测试和必要的契约已经落库；
- 本页状态与链接已经更新；
- 新增或更新一篇阶段学习文档，至少包含目标、前置知识、架构位置、核心流程、关键代码入口、设计取舍、验证方法和练习；
- 文档只描述已经实现并验证的行为；尚未实现的内容明确标为“下一步”，不能写成完成状态；
- 影响模块边界或技术决策时，同步更新 `docs/adr/`，学习文档链接到对应 ADR；
- 影响外部 API、事件或特征时，同步更新 `contracts/`，并在学习文档解释兼容性。

文件按实现顺序命名，例如：

```text
docs/learning/
├── README.md
├── 00-architecture-and-roadmap.md
├── phase-00-engineering-baseline.md
├── step-01-content-pipeline.md
├── step-02-search-baseline.md
└── template.md
```

这样既可以从头顺序学习，也能从某个功能反查实现、测试、契约和设计原因。
