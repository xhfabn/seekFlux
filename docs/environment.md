# 开发环境基线

版本基线记录在根目录 `.tool-versions`。使用 asdf/mise 的开发者进入目录后可安装对应的 JDK、Maven 和 Python；Docker Engine 与 Compose 由宿主机安装。

## 必需工具

| 工具 | 基线 | 用途 |
| --- | --- | --- |
| Temurin JDK | 21 LTS | 在线服务、Worker、Flink Job |
| Maven | 3.9.x | Java 多模块构建 |
| Python | 3.12.x | 训练与评测 Runner |
| Docker Engine | 26+ | 本地中间件容器 |
| Docker Compose | v2 | 本地环境编排 |

## 建议资源

核心中间件建议至少分配 4 CPU、8 GB 内存和 20 GB 磁盘。启用完整可观测 profile 时建议 12 GB 以上内存。

本地 Compose 是单节点开发配置，不具备生产高可用、安全加固或容量代表性。生产部署应使用托管/集群形态、密钥管理、TLS、备份与独立容量评估。

