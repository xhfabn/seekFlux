# SeekFlux Web

SeekFlux 的唯一前端工程，按产品角色组织为三个区域：

- 发现（C 端）：短视频内容消费结构，统一承载推荐、关键词搜索、相似内容和多轮 AI 搜索；
- 用户画像（B 端）：把冷启动兴趣保存到 Online Server，并查看曝光与互动事件；
- 内容工作台（B 端）：登记媒体、查询处理状态、校准并发布画像。

对象存储二进制上传尚未完成，因此文件选择仍显示明确占位；内容登记、画像发布和行为回流都只在真实后端成功后显示成功状态。

推荐 Feed 与 AI 搜索都不在前端模拟匹配。Feed 由 Online Server 使用 Redis 用户画像召回；AI 搜索由 Agent Server 保存会话目标、调用模型与 Search Tool，并把真实候选返回给同一套内容卡片。六类联调样例通过真实内容提交、Worker 画像生成和索引发布链路创建：

发现页会按后端返回的 request/trace/position 记录 `FEED`、`SEARCH`、`AGENT` 曝光与主动行为；用户画像页通过真实 `POST /v1/interactions:batch` 回传。服务端负责幂等、曝光归因、Outbox/Kafka、实时窗口和最终行为事实，前端不写死匹配或计数逻辑。用户画像页还会读取 `GET /v1/features/users/{userId}/short-term-interest`，展示后端计算的短期主题、特征版本和计算时间；发现页的排序仍完全以 Search/Feed 返回为准。

```bash
./seekflux.sh seed-demo
```

开发环境默认连接 `http://127.0.0.1:8081` 的 Content API、
`http://127.0.0.1:8080` 的 Online API 和 `http://127.0.0.1:8083` 的 Agent API。
可通过 `SEEKFLUX_CONTENT_API_BASE`、`SEEKFLUX_ONLINE_API_BASE` 与
`SEEKFLUX_AGENT_API_BASE` 覆盖。模型密钥只配置在仓库根目录且已忽略的 `.env`，不能进入 Web 环境变量或客户端 Bundle。

```bash
npm install
npm run dev -- --host 127.0.0.1 --port 3001
```
