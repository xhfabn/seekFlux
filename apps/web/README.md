# SeekFlux Web

SeekFlux 的唯一前端工程，按产品角色组织为三个区域：

- 发现（C 端）：纵向短视频消费结构，统一承载搜索、推荐和相似内容；
- 用户画像（B 端）：配置冷启动兴趣、查看曝光与互动事件；
- 内容工作台（B 端）：登记媒体、查询处理状态、校准并发布画像。

对象存储上传和 Interaction API 尚未完成，因此文件选择与实时回流会显示明确占位；不会伪报服务端成功。

开发环境默认连接 `http://127.0.0.1:8081` 的 Content API 和
`http://127.0.0.1:8080` 的 Online API。可通过
`SEEKFLUX_CONTENT_API_BASE` 与 `SEEKFLUX_ONLINE_API_BASE` 覆盖。

```bash
npm install
npm run dev -- --host 127.0.0.1 --port 3001
```
