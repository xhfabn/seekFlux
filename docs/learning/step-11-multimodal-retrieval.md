# Step 11：多模态媒体理解与跨模态检索

## 本阶段状态

- 状态：下一步
- 开始日期：2026-08-14
- 对应开发 Step：Step 11
- 对应 Agent Phase：Phase 4 可选深化
- 对应 ADR 与契约：[ADR-010](../adr/ADR-010-shared-space-multimodal-retrieval.md)、[OpenAPI](../../contracts/openapi/seekflux-v1.yaml)

本 Step 已经开始实现，但尚未满足真实模型、真实媒体、前端操作和固定评测共同验收的完成
门槛，因此不能标记为“已完成”。

## 要解决的问题

让上传内容在发布后读取真实图片像素和视频画面，并让文本、图片、视频查询都能在共享
向量空间召回图片和视频。当前切片刻意不把 OCR、ASR、视觉描述、时序视频模型和推荐
排序实验伪装成已经完成。

## 架构位置

```text
Content v2 published → Worker → MediaEmbeddingPort → SigLIP sidecar
                              → MediaSegmentIndex → Elasticsearch media segments

POST /v1/search/multimodal → MultimodalSearchUseCase
                           → MediaEmbeddingPort
                           → MediaSegmentRetriever → best segment/content
```

## 已实现的首个切片

- 定义文本、图片、视频统一的 Embedding Port 和带时间范围的媒体分段契约；
- 增加 SigLIP 2 Python sidecar，图片直接编码，视频通过 FFmpeg 每五秒抽取关键帧；
- Worker 在 `content.profile.published.v2` 后生成媒体分段向量，撤回时删除对应分段；
- Elasticsearch 使用独立的 `seekflux-media-segments-v1` dense-vector 索引；
- 增加 `POST /v1/search/multimodal`，支持 `TEXT`、`IMAGE`、`VIDEO` 查询，多个查询
  分段召回后按内容最高分去重，并返回命中的视频时间范围；
- Content Server 增加受类型和大小约束的 MinIO 文件直传；内容工作台可以直接上传媒体，
  发现页可以选择图片或视频发起跨模态查询并显示命中时间提示；
- `MULTIMODAL_ENABLED=false` 为默认值，不影响现有文本 Search/Feed/Agent；开启后模型或
  索引故障返回 503；
- `seekflux.sh` 能按开关管理模型 sidecar，macOS 使用 launchd，Ubuntu 使用 nohup/PID。

## 关键代码入口

| 入口 | 作用 | 建议阅读顺序 |
| --- | --- | --- |
| `contexts/search-context/.../MediaEmbeddingPort.java` | 模型供应商无关的共享向量接口 | 1 |
| `tools/multimodal/server.py` | SigLIP 2、图片编码与视频关键帧抽取 | 2 |
| `ContentMediaIndexWorker.java` | 发布事件到媒体分段索引 | 3 |
| `ElasticsearchMediaSegmentAdapter.java` | 分段 dense-vector 写入和 kNN | 4 |
| `MultimodalSearchApplicationService.java` | 多查询分段融合与内容去重 | 5 |
| `MultimodalSearchController.java` | 同步 JSON 查询 API | 6 |

## 如何验证

已执行：

```bash
bash -n seekflux.sh deploy/local/stack.sh
python3 -m py_compile tools/multimodal/server.py
mvn -pl contexts/search-context,platform/model-serving,platform/retrieval,apps/worker-runner,apps/online-server -am -DskipTests package
mvn -pl contexts/search-context -am test
```

编译、Python 语法检查和新增的跨模态多分段融合单测通过。完整 Spring 测试在当前 Codex
沙箱中被 Mockito/ByteBuddy 自附加权限阻断；其他已运行模块和 Controller 测试通过，这个
环境问题不能作为真实模型验收证据。

## 剩余完成门槛

1. 实际下载并启动固定版本 SigLIP 模型，使用至少一组真实图片和视频完成端到端验收；
2. 增加 OCR、ASR、视觉描述和受控标签的多路融合，记录来源、置信度和模型版本；
3. 建立固定跨模态查询集，分别报告文字搜图/视频、以图搜图/视频、视频搜视频 Recall@K；
4. 补齐索引重建、模型版本切换、Kafka 重试/DLQ、URI 出站安全和资源限流。

完成上述门槛并保留可复现证据后，才能进入 Step 12 模型排序与推荐实验。

当前路线与下一步只以[学习路线首页](README.md)为准。
