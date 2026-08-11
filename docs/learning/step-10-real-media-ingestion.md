# Step 10：真实媒体入库与可消费内容

## 本阶段状态

- 状态：已完成
- 完成日期：2026-08-11
- 对应开发 Step：Step 10
- 对应 Agent Phase：Phase 4 可选深化
- 对应需求：从外部导入真实视频/图文，按后端画像与检索词发现，并能播放或阅读
- 对应 ADR 与契约：[ADR-009](../adr/ADR-009-external-media-ingestion-and-provenance.md)、[OpenAPI](../../contracts/openapi/seekflux-v1.yaml)、`content.*.v2` 事件 Schema

## 要解决的问题

输入是带真实媒体、来源身份和标签的外部记录，输出必须是存入本地 MinIO、经 Content Pipeline 异步发布、可被 Search/Feed 返回并在前端播放或阅读的内容。前端不得用静态数据判断画像匹配。

本阶段没有导入用户链接仓库中并不存在的图片目录，也没有宣称取得原平台内容的传播权；模型打标、模型排序和推荐实验不在本阶段。

## 架构位置

```text
Pixabay API / Qilin metadata + local images / JSONL Manifest
                         │
                         ▼
              tools/media_import.py
             ┌───────────┴───────────┐
             ▼                       ▼
  MinIO seekflux-media       Content Server (v2)
                                     │
                         PostgreSQL + Outbox
                                     │
                                    Kafka
                                     │
                        Profile Worker → 标签门槛
                                     │
                         Search Index Worker
                                     ▼
 Elasticsearch ← Online Search/Feed ← Web 视频/图文详情
```

导入器是边界 Adapter；它只通过 Content HTTP 输入契约登记内容。画像匹配仍由 Recommendation Context 执行，搜索仍由 Search Context 执行。

## 完成了什么

- `Content` 新增 `ContentType`、`assetUris`、`body` 与 `ContentSource`；V7 迁移为外部来源建立唯一索引。
- 提交接口对 `(provider, externalId)` 幂等，重放不会重复登记或重复发送 Outbox。
- v2 内容生命周期事件携带视频/图文、多资源、正文与来源；v1 契约保留不变。
- MinIO 初始化 `seekflux-media`，本地允许浏览器匿名只读；媒体不再依赖永久热链。
- `tools/media_import.py` 支持：
  - `pixabay`：调用正式 API，下载图片或可播放 MP4；
  - `qilin`：读取 JSON、JSONL 或 Parquet 元数据并上传本地图片；
  - `manifest`：接入其他合法来源或自有数据；
  - `.local/imports/state.json` 断点记录、后端双重幂等、单条失败后继续或 `--fail-fast`。
- Worker 会合并来源标签、Hashtag 与受控关键词；标签为空时停在 `PROFILE_READY`，不会进入搜索和推荐。
- Elasticsearch、Search Hit、Recommendation Item 全链路返回内容类型、多资源、正文与来源信息。
- C 端卡片按类型显示视频或图片；详情层可以播放视频，或横向浏览图文资源并阅读正文、查看来源。
- B 端内容工作台可选择视频/图文并填写图文正文。

## 与 Agent 主线的关系

Agent Phase 3 已在 Step 7 完成。本切片为 Agent/Search Tool 和 Direct Search 补充真实可消费候选，不改变 Agent Runtime。AI 搜索返回的仍是 Search Tool 的真实后端结果，因此新字段自然进入 Agent 结果卡片；Direct Search 和规则 Feed 的确定性回退保持独立。

## 核心流程与失败语义

1. 导入器读取外部记录，以 Provider 和外部 ID 检查本地断点。
2. 下载媒体并上传 MinIO；只有所有资源成功后才调用 Content API。
3. Content 以外部身份幂等登记并写 Outbox；Worker 生成画像。
4. 有标签则发布 v2 事件并写 Elasticsearch；无标签保持待人工校准。
5. Search/Feed 返回同一内容事实，Web 依据 `contentType` 展示。

失败处理：下载或上传失败不登记内容；登记成功但本地状态写入前崩溃时，重放由数据库唯一身份返回原内容；MinIO 不可用时导入停止而线上读取不受影响；标签为空时不分发；旧 v1 Topic 不会被 v2 Consumer 误读。

## 关键代码入口

| 入口 | 作用 | 建议阅读顺序 |
| --- | --- | --- |
| `contexts/content-context/.../Content.java` | 内容类型、多资源、正文与来源领域约束 | 1 |
| `platform/persistence/.../V7__content_media_and_provenance.sql` | 持久化与外部身份唯一性 | 2 |
| `tools/media_import.py` | Pixabay、Qilin、Manifest 下载、上传、断点与登记 | 3 |
| `apps/worker-runner/.../BasicContentProfileWorker.java` | 确定性标签与无标签发布门槛 | 4 |
| `platform/retrieval/.../ElasticsearchSearchAdapter.java` | 新媒体字段的索引和召回映射 | 5 |
| `apps/web/app/SeekFluxApp.tsx` | 视频/图文卡片、详情与内容工作台 | 6 |
| `tools/verify_media_flow.py` | 真实媒体全链路验收 | 7 |

## 设计取舍

- 先用一套内容聚合和 `ContentType`，避免在搜索/推荐前复制两套生命周期；代价是视频转码与图文资源处理以后要在媒体子系统继续分化。
- 导入器使用 Python 标准库和现有 `mc`，不把第三方 SDK带入 Java 服务；Parquet 是可选 `pyarrow` 依赖。
- 第一版不调用 LLM 打标签，保证无 Key 可回归；规则无法判断时必须人工校准，不能用空标签污染推荐。
- 来源许可作为审计事实记录，不据此自动判定“可商用”。正式扩量前仍要逐源审核条款。

## 如何验证

```bash
./seekflux.sh restart
mvn -q test
cd apps/web && npm test

# 有自己的 Pixabay Key 后，在根目录 .env 设置 PIXABAY_API_KEY
python3 tools/media_import.py pixabay --type video --query "travel" --limit 10
python3 tools/media_import.py pixabay --type image --query "coffee" --limit 10

# Qilin 元数据与图片已经合法下载到本机时
python3 tools/media_import.py qilin \
  --metadata /path/to/qilin.jsonl \
  --asset-root /path/to/qilin-images \
  --limit 1000

# 对已导入内容做发布、媒体类型、搜索和画像 Feed 验收
python3 tools/verify_media_flow.py <video-content-id> <article-content-id>
```

## 完成证据

- `mvn -q test` 在 JDK 21、沙箱外通过；沙箱内 Mockito 无法附加测试 Agent，是环境限制而非断言失败。
- `apps/web` 的 `npm test` 通过，包含生产构建和三项渲染契约测试。
- 真实 E2E 导入公开 MP4 与 JPEG 到 `seekflux-media`，内容 ID 分别为 `cb6f9577-336a-4089-8fd1-080508efdd3c`、`c11c32ca-10b0-418b-99e3-ea0ee048b1e1`；两者均到达 `PUBLISHED`。
- PostgreSQL Outbox 只读核对显示两条内容的 `content.submitted.v2`、`content.profile.ready.v2`、`content.profile.published.v2` 共六条事件全部为 `PUBLISHED`。
- `tools/verify_media_flow.py` 验证两条 MinIO URL 返回正确的 `video/*` / `image/*`，关键词搜索和 `旅行/咖啡` 用户画像 Feed 均命中两条内容。
- 同一 Manifest 重放输出 `imported=0 skipped=2 failed=0`。
- Qilin JSONL 字段映射 Dry Run 输出 `ARTICLE`、正文、`咖啡/旅行` 标签和本地 `file://` 图片源，证明 Adapter 可以在正式数据下载后复用同一导入主链路；这不冒充已经取得完整 Qilin 图片集。

## 本阶段可以学到什么

这里可以观察“导入幂等”和“消息幂等”是两层不同保证：本地 State 减少重复下载，数据库唯一身份阻止重复内容，Outbox 再保证生命周期事件可靠发送。也可以看到媒体类型必须进入领域和索引契约，不能只在前端按文件后缀猜测。

## 练习与自检问题

- 读代码：沿一条 `ARTICLE` 从 Submit Request 追到 Elasticsearch `_source`，列出字段在哪里被校验。
- 小改动：给 `manifest` 增加第二张图并验证详情层横向浏览，确认主媒体仍为第一项。
- 设计题：生产环境加入转码时，原始文件、转码产物、封面与字幕应该如何版本化，才能让已发布内容稳定回放？

## 常见问题与排查

- `PIXABAY_API_KEY is required`：只在本地 `.env` 写入自己的 Key，不提交仓库。
- `MinIO client is missing`：先执行 `./seekflux.sh start` 或 `./seekflux.sh up`。
- 内容停在 `PROFILE_READY`：检查来源标签、正文 Hashtag 或受控词是否至少产生一个标签，然后在内容工作台人工校准并发布。
- 媒体能下载但浏览器打不开：检查 `MINIO_PUBLIC_BASE` 是否是浏览器可访问的地址，以及 `seekflux-media` 是否只有匿名下载权限。
- Qilin 图片为空：用户链接仓库没有提交实际图片，必须按数据集说明单独取得资源，并确认原始媒体使用权。

## 下一步

当前下一步由[学习路线首页](README.md)定义为 Step 11「模型排序与推荐实验」。正式数据扩量属于本阶段能力的运营使用：先用自己的 Pixabay Key 做 `50 视频 + 100 图文` 小批审核，再逐步导入已合法取得的 Qilin 子集；它不阻塞模型实验代码，但模型实验只能使用许可、标签和行为归因均明确的数据。
