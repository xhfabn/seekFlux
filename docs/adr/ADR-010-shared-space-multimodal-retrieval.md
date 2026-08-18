# ADR-010：共享向量空间的多模态检索

- 状态：接受
- 日期：2026-08-14

## 背景

Step 10 让视频和图文成为可消费结果，但搜索向量仍只来自标题、正文、标签和转写。把
视觉大模型生成的描述再次送入文本检索会丢失外观、构图和短暂镜头等像素信息，也无法
稳定支持以图搜图、以图搜视频和以视频搜视频。

## 决策

文本查询、图片和视频关键帧使用同一版本的 SigLIP 2 双塔模型编码到共享向量空间。
视频按固定抽样策略产生多个时间分段，不把整条视频压缩为单一向量。媒体分段单独写入
`seekflux-media-segments-v2`，查询视频的多个向量分别 kNN 召回，再按内容融合去重。

模型运行在独立 Python sidecar；Search Context 只定义 `MediaEmbeddingPort`、
`MediaUnderstandingPort`、`MediaSegmentIndex` 和 `MediaSegmentRetriever`，Java Adapter 通过
普通同步 HTTP 调用模型。
模型调用不引入 Reactor，也不允许业务代码直接依赖 Transformers 或某个商业 API。

能力由 `MULTIMODAL_ENABLED` 显式开启。关闭时现有 Direct Search、Feed 和 Agent Search
保持原样；开启后模型或媒体索引不可用时，多模态接口返回明确的 503，不伪装成空结果。

第二个切片增加多路理解，但不把生成文本替代原始视觉信号：RapidOCR 读取画面文字，
faster-whisper 读取视频音轨，BLIP 视觉描述作为可选高成本通道，内容标题、正文、标签和
人工转写作为可信元数据通道。每条证据保存通道、置信度、时间范围与模型版本。文本跨模态
查询分别走 SigLIP kNN 和 `understanding_text` BM25，再在应用层以加权 RRF 融合；图片和
视频查询不凭空生成查询词，仍走原始视觉向量。

理解通道以 `AVAILABLE`、`DISABLED`、`DEGRADED` 记录。OCR、ASR 或 Caption 单路失败不
阻断视觉分段入库；查询侧理解文本通道失败时返回可见降级，所有视觉召回失败才返回 503。

## 影响

- 同一个模型版本必须同时用于入库和查询，切换维度或模型需新建索引并重建媒体向量；
- 首次本地启动需要 Python、PyTorch、FFmpeg 和模型下载，资源成本明显高于文本基线；
- 当前五秒关键帧是可验证基线，不能代表动作、时序或音频语义已经完成；
- OCR、ASR、视觉描述和元数据已成为版本化理解证据，不替代原始视觉向量；
- Caption 默认关闭，OCR/ASR 默认随总开关启用，避免无意加载全部模型；
- 生产环境需增加 URI 出站控制、对象大小限制、模型缓存和 GPU 资源隔离。
