# Agent Runtime 发布手册

对外发布只包含：

- `io.github.xhfabn.seekflux:seekflux-agent-runtime-core`
- `io.github.xhfabn.seekflux:seekflux-agent-runtime-spring-boot-autoconfigure`

JDBC、Redis、模型 Provider、MCP、Micrometer 和 SeekFlux 业务 Adapter 不在发布范围内。

## 首次发布前置

1. Apache-2.0 已由仓库所有者选定；根 `LICENSE`、两个公共 POM 的 `<licenses>` 元数据和
   JAR 内 `META-INF/LICENSE` 已配置。发布前仍需确认拟发布代码的授权归属。
2. 2026-09-27 已在 Maven Central Publisher Portal 确认 `io.github.xhfabn` 为 Verified。
3. 仓库所有者已生成 GPG 签名密钥，并确认公钥可从 keyserver 检索；RC1 工作流已完成实际签名和 Portal 校验。
4. GitHub Actions 的 Repository secrets 中已存在 `CENTRAL_USERNAME`、`CENTRAL_TOKEN`、
   `GPG_PRIVATE_KEY` 和 `GPG_PASSPHRASE` 四个名称。Secret 值不可读；RC1 staging 工作流已验证其可用性。

## RC1 当前状态（2026-09-27）

- 发布分支：`codex/agent-runtime-publishable-v1`；源提交：`e0f2139980028fa9b7215e53ef6bab2110d82cc4`；
  Tag：`agent-runtime-v1.0.0-RC1`。
- [GitHub Actions 运行](https://github.com/xhfabn/seekFlux/actions/runs/36301443634) 已成功完成测试、边界检查、
  GPG 签名和 Central staging。
- Portal deployment `c65b92a7-cd6f-41f4-8a20-f71cbbd6c927` 显示 `VALIDATED`，两个组件均通过校验；
  每个组件均有 POM、主 JAR、sources JAR、Javadoc JAR、签名和校验和。
- 尚未点击 Portal 最终 Publish；`1.0.0-RC1` 尚非公开发布。版本正式发布后不可覆盖或删除，
  后续迭代须使用新的版本号；发布后仍须做空缓存外部消费者验收。

## 版本更新

每次发布前保持以下位置的版本完全一致：

- `platform/agent-runtime/pom.xml`
- `platform/agent-runtime-spring-boot-autoconfigure/pom.xml`
- 根 `pom.xml` 的 `seekflux.agent-runtime.version`
- 两个模块 README 中的消费者示例

Central 上的版本不可覆盖。发现问题时必须使用新版本，不能重新发布同一 GAV。

## 本地验证

使用 JDK 21 执行：

```bash
mvn -q test
mvn -pl platform/agent-runtime,platform/agent-runtime-spring-boot-autoconfigure \
  -am package -Pcentral-release -Dgpg.skip=true -DskipTests
jdeps --multi-release 21 --recursive -s \
  platform/agent-runtime/target/seekflux-agent-runtime-core-1.0.0-RC1.jar
```

`jdeps` 结果必须只有 `core JAR -> java.base`。Auto-configuration JAR 必须同时包含：

- `META-INF/spring-configuration-metadata.json`
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

两个主 JAR 还必须包含 `META-INF/LICENSE`，两个发布 POM 的许可证元数据必须一致。

再把两个制品安装到本地仓库，从独立消费者 POM 只引入 Auto-configuration；compile
dependency tree 必须只有 `auto-configuration -> core`。

## GitHub 与 Central 流程

1. 在已验证的发布分支上提交并推送，对目标提交创建 `agent-runtime-v<version>` Tag；
   本次 RC1 从 `codex/agent-runtime-publishable-v1` 发布。若使用手工 `workflow_dispatch`，
   workflow 文件需先存在于默认分支；当前分支直接推送 Tag 可触发 `push.tags` 工作流。
2. `Release Agent Runtime` workflow 重新运行测试、边界检查、sources/Javadoc 构建和 GPG 签名。
3. Workflow 只把 deployment 暂存到 Central Portal，`autoPublish=false`。
4. 在 Portal 中核对 POM、许可证、签名、sources、Javadoc 和两制品依赖关系，再手工
   publish；如果验收失败则 drop staging deployment。
5. 发布后用空 Maven 缓存创建最小 Java 和 Spring Boot 消费者，以 Central 制品做最终验收。

详细边界和设计原因见 [ADR-015](../../docs/adr/ADR-015-agent-runtime-publication-boundary.md)。
