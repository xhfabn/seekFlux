# SeekFlux Agent Runtime Core

`seekflux-agent-runtime-core` is the provider-neutral and infrastructure-neutral execution kernel.
Its main artifact has no third-party compile or runtime dependency and targets Java 21.

```xml
<dependency>
    <groupId>io.github.xhfabn.seekflux</groupId>
    <artifactId>seekflux-agent-runtime-core</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

The Core contains the Loop, Router, Context Engine, Tool execution, cancellation, capability,
checkpoint, durable-wait and side-effect protocols. It deliberately contains no JDBC, Redis,
model-provider, MCP, Micrometer or Spring implementation. Hosts provide their own implementations
through the Runtime SPI.

At minimum, a host assembling the complete Router supplies `AgentSessionStore`,
`ExecutionAuthorityStore`, `PromptResolver`, Agent definitions and their `LlmClient` instances.
Business Tools implement `AgentTool`. Recovery, distributed cancellation, durable wait, context
compaction, cross-node Push and observability ports are required only when the corresponding
feature is enabled.

`MUTATING` Tools are rejected by the default registration policy. A host must explicitly opt in,
provide a durable side-effect ledger through `AgentRecoveryStore`, and preserve the Runtime's
idempotency and reconciliation invariants.

Spring Boot applications normally depend only on
`seekflux-agent-runtime-spring-boot-autoconfigure`, which brings this Core transitively without
bringing Spring Boot itself.

Build and test this artifact with:

```bash
mvn -pl platform/agent-runtime test
```

The `central-release` Maven profile attaches source and Javadoc JARs, signs every artifact and uses
the Central Publisher Portal plugin. The repository owner selected Apache-2.0; the root
[LICENSE](../../LICENSE) and both published JARs carry it. Version `1.0.0-RC1` is publicly
available from Maven Central and has passed an empty-cache external-consumer compile. See the
[release manual](RELEASING.md).
