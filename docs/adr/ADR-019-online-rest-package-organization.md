# ADR-019：Online REST 按业务分包与服务内错误响应

- 状态：Accepted
- 日期：2026-10-20

## 决策

`online-server` 的 `api` 下按 `search / recommendation / interaction / feature / interest`
组织 HTTP 入口，每个业务的 Controller 与专用请求、响应 DTO 同包；测试使用对应的包结构。
当前类数量不需要进一步分出 controller、request、response 层级。

服务内公共错误模型 `ApiError` 和 `OnlineExceptionHandler` 放在 `api/error`。
Advice 限定 online API 包；参数错误码按 Spring 实际匹配的 HandlerMethod 所属包选择，
不依赖请求 URI，未识别的 Controller 使用 `INVALID_REQUEST`。

Content、Agent 服务的业务异常映射继续由各自 Handler 负责。暂不抽取跨服务 Web 模块，
也不向 `shared` 添加 HTTP 类型；只有跨服务契约和实际复用范围明确后再评估。

## 后果与边界

HTTP 路径、成功响应、多模态启用开关保持现有语义。已有搜索、交互、推荐参数错误码保持兼容；
兴趣和实时特征的参数错误分别修正为 `INVALID_INTEREST_REQUEST`、`INVALID_FEATURE_REQUEST`。
错误响应继续使用 `code / message / timestamp`，409 和 503 的异常映射保持原有语义。
未匹配到本 Advice 所声明异常的框架错误仍由 Spring 处理，不承诺所有错误统一包装。

增加业务包或移动 Controller 时须同步错误码映射与 HTTP 回归测试；这不是业务域模块拆分。
契约以 [OpenAPI](../../contracts/openapi/seekflux-v1.yaml) 为准。
