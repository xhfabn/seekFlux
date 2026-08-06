# SeekFlux Web

SeekFlux 的闭环前端工作台，按架构中的三条主数据流组织为：

- 内容中枢：登记内容、查询处理状态、校准并发布画像；
- 发现引擎：关键词搜索、热门/兴趣/相似内容 Feed；其中推荐 Feed 对应已完成的 Step 3；
- 反馈回路：记录曝光与互动，批量提交 Interaction API；未接通时保存在浏览器本地队列。

开发环境默认连接 `http://127.0.0.1:8081` 的 Content API 和
`http://127.0.0.1:8080` 的 Online API。可通过
`SEEKFLUX_CONTENT_API_BASE` 与 `SEEKFLUX_ONLINE_API_BASE` 覆盖。

```bash
npm install
npm run dev
```
