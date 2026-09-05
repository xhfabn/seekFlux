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
- Linux root 本地启动已能让项目 PostgreSQL 以 `postgres` 用户、Elasticsearch 以自动创建的
  `seekflux` 用户运行；Java 启动改为 `clean package`，避免旧 `target/classes` 资源进入新 Jar；
- Web 本地启动使用 Vinext Node Runtime，Cloudflare 构建模式保持不变；PID 文件直接跟踪
  Vinext 服务进程，`apps-down` 不再遗留占用 3001 的孤儿进程；
- Web 启动入口支持通过 `.env` 配置 `WEB_SERVER_HOST` 与 `WEB_SERVER_PORT`；受控云开发环境
  可以只把 Web 绑定到 `0.0.0.0:10000`，其余数据、中间件和模型端口继续保持本机回环；
- Sidecar 和真实媒体验收脚本对 `localhost`、`127.0.0.1`、`::1` 强制绕过系统 HTTP 代理，
  本地 MinIO 媒体不再被代理误报 502；
- 媒体分段检索结果在每条召回路由内先按内容去重，再进入 RRF；长视频不能仅凭更多索引分段
  累加分数压过完全相同的图片，并有回归测试覆盖该排序边界。

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

2026-09-05 已在 Ubuntu 20.04、JDK 21、Node 22、Python 3.12、RTX 4090 环境完成一次
真实模型和真实上传链路验收。固定模型 `google/siglip2-base-patch16-224` 运行于 CUDA，健康接口
报告视觉、OCR、ASR 启用，进程占用约 2 GiB 显存。通过 Content API 上传一张生成图片和一段
11 秒带合成语音的视频，二者经 PostgreSQL/Outbox、Kafka、Worker 后发布，并在
`seekflux-media-segments-v2` 形成 1 个图片分段和 2 个视频分段：

- 图片 OCR 得到 `SEEKFLUXCOFFEELAB`，置信度 0.9938；
- 视频 OCR 得到 `SEEKFLUXMOUNTAINJOURNEY`，置信度约 0.994；
- 视频 ASR 得到 `A mountain journey under a blue sky seek flux multimodal retrieval.`，
  置信度 0.9957，模型版本 `faster-whisper-small`；
- 文字查询 `mountain journey under a blue sky` 把视频排第 1，同时命中 `VISUAL` 和
  `UNDERSTANDING_TEXT`，无降级；
- 使用原图查询时相同图片排第 1；使用原视频查询时相同视频排第 1，命中视频范围
  `5000–10000 ms`；
- Web 首页返回 200，经 `/api/bridge/online/v1/search/multimodal` 查询返回 200；普通 Search、
  Feed 和 MinIO 对象可达性也由 `tools/verify_media_flow.py` 验收通过。
- 云开发环境把 Web 配置为 `WEB_SERVER_HOST=0.0.0.0`、`WEB_SERVER_PORT=10000` 后，`ss` 确认
  Vinext 监听所有 IPv4 接口；回环地址、容器网卡地址和模拟平台转发域名的请求均返回 200，
  `./seekflux.sh status` 同时报告完整链路全部为 UP。

本次实际执行：

```bash
bash -n seekflux.sh deploy/local/stack.sh deploy/local/run-web-app.sh deploy/local/seed-demo-content.sh
.runtime/multimodal-venv/bin/python -m py_compile tools/multimodal/server.py tools/verify_media_flow.py
./seekflux.sh up
./seekflux.sh status
ss -lntp '( sport = :10000 )'
curl --noproxy '*' http://127.0.0.1:10000/
mvn -pl contexts/search-context -am -Dtest=MultimodalSearchApplicationServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl apps/worker-runner,apps/online-server -am -Dtest=ContentMediaIndexWorkerTest,MultimodalSearchControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
cd apps/web && npm run lint && npm test
python3 tools/verify_media_flow.py \
  35d855a6-26b3-4f3b-b0b6-819571ed053e \
  90818b0d-05c5-43cc-ab02-906582da7806 --query E2E
```

Shell/Python 检查、Search Context 3 个多模态融合测试、Worker/Controller 定向测试、Web lint、
Web 构建和 3 个渲染测试均通过。首次启动还实际验证了 SigLIP 与 Whisper 权重下载、CUDA 加载、
MinIO 上传、异步索引、三种查询输入以及 Web Bridge。该两条样本只证明链路可运行，不是固定
查询集，也不构成 Recall@K 或消融基线。

## 剩余完成门槛

1. 建立固定跨模态查询集，分别报告文字搜图/视频、以图搜图/视频、视频搜视频 Recall@K，
   并分别做视觉、OCR、ASR、Caption 消融；
2. 补齐 v1 → v2 索引重建命令、模型版本切换、Kafka 重试/DLQ、URI 出站安全和资源限流；
3. 把通道延迟、失败率、证据覆盖率和索引积压接入可观测性。

完成上述门槛并保留可复现证据后，才能进入 Step 12 模型排序与推荐实验。

当前路线与下一步只以[学习路线首页](README.md)为准。
