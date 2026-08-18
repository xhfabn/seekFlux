# SeekFlux 多模态模型服务

该进程提供两类能力：SigLIP 2 把文本、图片和视频关键帧编码到共享向量空间；媒体理解
端点再按需执行 RapidOCR、faster-whisper ASR 和 BLIP 视觉描述。每路结果都包含来源、
置信度、媒体时间范围和模型版本，一路失败只会标记 `DEGRADED`，不会丢弃其他结果。

```bash
python3 -m venv .runtime/multimodal-venv
.runtime/multimodal-venv/bin/pip install -r tools/multimodal/requirements.txt
.runtime/multimodal-venv/bin/python tools/multimodal/server.py
```

模型依赖要求 Python 3.10–3.12（RapidOCR/ONNX Runtime 暂不支持 Python 3.14）。启动脚本
会优先选择 3.12、3.11、3.10，并自动重建版本不兼容的旧虚拟环境。macOS 可执行
`brew install python@3.12 ffmpeg`，Ubuntu 需提供对应的 `python3-venv` 与 `ffmpeg`。

在根目录 `.env` 中设置 `MULTIMODAL_ENABLED=true` 后重启 Worker 与 Online Server。

默认开启 OCR 和 ASR，视觉描述因额外模型内存占用而默认关闭：

```dotenv
MULTIMODAL_OCR_ENABLED=true
MULTIMODAL_ASR_ENABLED=true
MULTIMODAL_ASR_MODEL_ID=small
MULTIMODAL_CAPTION_ENABLED=false
MULTIMODAL_CAPTION_MODEL_ID=Salesforce/blip-image-captioning-base
```

- `POST /v1/embeddings`：查询侧只生成共享空间向量；
- `POST /v1/understand`：入库侧一次下载媒体，同时返回视觉向量和多路理解证据；
- `GET /health`：返回配置启用状态；一次媒体请求内的真实失败状态在 `channelStatuses` 中。

媒体理解索引是 `seekflux-media-segments-v2`。从 v1 升级后必须重新发布或重放内容事件，
不能混用缺少理解字段的旧分段。生产环境还应提前缓存模型、用 GPU worker 隔离视觉模型、
限制媒体 URI 出站范围，并把失败指标接入监控。
