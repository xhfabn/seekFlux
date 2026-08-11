# ADR-009：外部媒体入库、来源身份与发布门槛

- 状态：已接受
- 日期：2026-08-11

## 背景

SeekFlux 原先只保存一个 `mediaUri`，默认所有内容都是视频。演示地址没有落入项目自己的对象存储，图文会被前端误当成视频，而且外部记录没有稳定身份、来源页或许可信息。这样无法安全地批量导入真实内容，也无法验证视频播放、图文详情、搜索和画像推荐的完整链路。

用户指定的 [Rednote-Qilin-Search-Rec-System](https://github.com/WBoxian/Rednote-Qilin-Search-Rec-System) 仓库主要提供检索实现；README 提到的本地图片与约 198 万笔记没有随 Git 仓库提交。上游 [Qilin](https://github.com/RED-Search/Qilin) 与 [Hugging Face 数据页](https://huggingface.co/datasets/THUIR/qilin) 可作为图文元数据入口，但图片需要单独取得，且数据集许可不能自动替代原平台媒体的传播权。可播放视频另由提供明确 API 与来源页的素材源导入，第一版适配 [Pixabay API](https://pixabay.com/api/docs/)。

## 决策

1. 内容聚合新增 `VIDEO` / `ARTICLE` 类型、主媒体、最多 20 个资源 URI、图文正文和结构化来源字段。
2. 外部来源以 `(source_provider, external_id)` 作为稳定幂等身份，数据库使用部分唯一索引；重复请求返回已存在内容，不产生第二条 Outbox 事件。
3. 媒体先下载到 SeekFlux 管理的 `seekflux-media` Bucket，再登记可访问 URI。导入状态写入 `.local/imports/state.json`，下载或登记失败后可以按记录继续。
4. `content.submitted/profile.ready/profile.published` 升级为 v2 事件；v1 Schema 和 Topic 保留为历史契约，不原地改变其含义。
5. 基础画像 Worker 合并来源标签、正文 Hashtag 与有限受控词表。最终标签为空时只进入 `PROFILE_READY`，不自动发布；人工补齐标签后仍可走原有发布接口。
6. 搜索索引与搜索/推荐响应携带类型、资源、正文和来源。C 端按类型渲染真实 `<video>` 或 `<img>`，详情层展示视频播放器或图文资源、正文和原始来源。
7. 本地开发的媒体 Bucket 允许匿名只读，便于浏览器播放。生产环境不得照搬该公开策略，应放到 CDN/对象存储网关后并配置域名、缓存、鉴权和内容审核。

## 结果

- Qilin JSON/JSONL/Parquet 元数据、本地图片，Pixabay 图片/视频，以及规范化 Manifest 都有统一入口。
- 导入、内容画像、索引、搜索和推荐共享同一份后端标签事实，前端不做伪匹配。
- 每条外部内容可以追溯来源页、作者和许可声明，但字段只记录声明，不代表 SeekFlux 已取得传播权。
- API Key 只从本地 `.env` 读取；没有 Key 时不伪造 Pixabay 成功记录。

## 未包含

- 自动绕过登录、反爬或平台访问控制；
- 把小红书/抖音页面抓取当作默认数据源；
- 大规模版权审核、内容审核、病毒扫描、转码、封面抽帧和 CDN；
- 模型打标。首版只提供确定性规则标签和人工校准门槛，模型评测顺延到 Step 11。
