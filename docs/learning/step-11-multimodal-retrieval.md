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
向量空间召回图片和视频，并把画面文字、音轨、视觉描述和已有内容元数据作为可追溯的
独立理解证据。当前切片仍不把模型代码和单测冒充真实模型评测，也不把关键帧模型冒充
时序视频理解。

## 架构位置

```text
Content v2 published → Worker → MediaUnderstandingPort → SigLIP / OCR / ASR / Caption
                              → versioned evidence + vectors
                              → MediaSegmentIndex → Elasticsearch media segments v2

POST /v1/search/multimodal → MultimodalSearchUseCase
                           → MediaEmbeddingPort
                           → visual kNN + understanding BM25 → weighted RRF
```

## 已实现切片

- 定义文本、图片、视频统一的 Embedding Port 和带时间范围的媒体分段契约；
- 增加 SigLIP 2 Python sidecar，图片直接编码，视频通过 FFmpeg 每五秒抽取关键帧；
- Worker 在 `content.profile.published.v2` 后生成媒体分段向量，撤回时删除对应分段；
- Elasticsearch 使用独立的 `seekflux-media-segments-v2` dense-vector 与理解文本索引；
- 增加 `POST /v1/search/multimodal`，支持 `TEXT`、`IMAGE`、`VIDEO` 查询，多个查询
  分段召回后按内容融合去重，并返回命中的视频时间范围；
- Content Server 增加受类型和大小约束的 MinIO 文件直传；内容工作台可以直接上传媒体，
  发现页可以选择图片或视频发起跨模态查询并显示命中时间提示；
- `MULTIMODAL_ENABLED=false` 为默认值，不影响现有文本 Search/Feed/Agent；开启后模型或
  索引故障返回 503；
- `seekflux.sh` 能按开关管理模型 sidecar，macOS 使用 launchd，Ubuntu 使用 nohup/PID。
- Sidecar 新增 `/v1/understand`：一次媒体读取同时生成视觉分段向量、RapidOCR 画面文字、
  faster-whisper 音轨转写和可选 BLIP 视觉描述；OCR/ASR 默认开启，Caption 因资源成本默认关闭；
- 每条理解证据包含通道、文本、置信度、起止时间和模型版本，Worker 另把标题、正文、摘要、
  标签和已有人工转写记录为 `METADATA` 证据；
- 媒体索引升级到 `seekflux-media-segments-v2`，保存 `understanding_text`、证据数组和各通道
  `AVAILABLE/DISABLED/DEGRADED` 状态；单路 OCR/ASR/Caption 失败不丢弃视觉索引；
- 文本跨模态查询同时走 SigLIP 共享空间 kNN 和理解文本 BM25，以加权 RRF 做后端融合；
  图片/视频查询仍只依赖真实媒体向量，不在前端伪造标签；
- API 返回命中通道、理解证据、降级状态与不可用通道，Web 使用后端返回的命中通道展示原因。

## 关键代码入口

| 入口 | 作用 | 建议阅读顺序 |
| --- | --- | --- |
| `contexts/search-context/.../MediaEmbeddingPort.java` | 模型供应商无关的共享向量接口 | 1 |
| `contexts/search-context/.../MediaUnderstandingPort.java` | 多路理解与逐路状态接口 | 2 |
| `tools/multimodal/server.py` | SigLIP、OCR、ASR、Caption 与视频关键帧 | 3 |
| `ContentMediaIndexWorker.java` | 发布事件到媒体分段索引 | 4 |
| `ElasticsearchMediaSegmentAdapter.java` | dense-vector 与理解文本索引 | 5 |
| `MultimodalSearchApplicationService.java` | 多分段和多路 RRF 融合 | 6 |
| `MultimodalSearchController.java` | 同步 JSON 查询 API | 7 |

## 如何验证

已执行：

```bash
bash -n seekflux.sh deploy/local/stack.sh
python3 -m py_compile tools/multimodal/server.py
mvn -pl contexts/search-context,platform/model-serving,platform/retrieval,apps/worker-runner,apps/online-server -am -DskipTests package
mvn -pl contexts/search-context -am test
mvn -pl apps/worker-runner,apps/online-server -am -Dtest=ContentMediaIndexWorkerTest,MultimodalSearchControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

编译和 Python 语法检查通过；Search Context 的视觉多分段、文本双路 RRF 测试通过；
Worker 的版本化证据入库测试和 Controller 契约测试在 JDK 21 下通过。使用当前 shell 默认
JDK 25 跑全量 Worker 测试时，旧 `BasicContentProfileWorkerTest` 仍被 Mockito/ByteBuddy
自附加权限阻断，这不是新增测试失败。当前 macOS 已安装 FFmpeg 与 Python 3.12，但
Homebrew Python 的 `pyexpat` 与系统 `libexpat` 符号不兼容，依赖和模型尚未成功安装；因此
没有把 Sidecar 启动或真实媒体结果写成验收通过。

## 剩余完成门槛

1. 实际下载并启动固定版本 SigLIP 模型，使用至少一组真实图片和视频完成端到端验收；
2. 建立固定跨模态查询集，分别报告文字搜图/视频、以图搜图/视频、视频搜视频 Recall@K，
   并分别做视觉、OCR、ASR、Caption 消融；
3. 补齐 v1 → v2 索引重建命令、模型版本切换、Kafka 重试/DLQ、URI 出站安全和资源限流；
4. 把通道延迟、失败率、证据覆盖率和索引积压接入可观测性。

完成上述门槛并保留可复现证据后，才能进入 Step 12 模型排序与推荐实验。

当前路线与下一步只以[学习路线首页](README.md)为准。
