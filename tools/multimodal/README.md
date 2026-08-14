# SeekFlux 多模态模型服务

该进程使用同一个 SigLIP 2 模型把文本、图片和视频关键帧编码到共享向量空间。模型服务
独立于 Java 业务进程，首次启动会从 Hugging Face 下载模型；视频解析还需要 `ffmpeg`。

```bash
python3 -m venv .runtime/multimodal-venv
.runtime/multimodal-venv/bin/pip install -r tools/multimodal/requirements.txt
.runtime/multimodal-venv/bin/python tools/multimodal/server.py
```

在根目录 `.env` 中设置 `MULTIMODAL_ENABLED=true` 后重启 Worker 与 Online Server。
生产环境应提前把模型缓存在受控目录，并用 GPU worker 托管该进程；不要在 HTTP 请求中
临时加载模型。
