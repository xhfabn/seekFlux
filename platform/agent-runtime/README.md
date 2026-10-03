# SeekFlux Agent Runtime Core

`seekflux-agent-runtime-core` is the provider-neutral and infrastructure-neutral execution kernel.
It targets Java 21. Current source is `1.0.0-RC2-SNAPSHOT` (not published) and includes a default
MCP client with Jackson as its only third-party runtime dependency family. Spring, Redis, JDBC,
model providers and metrics implementations are not included. Published RC1 is unchanged and
does not contain MCP; the dependency below selects that published release.

```xml
<dependency>
    <groupId>io.github.xhfabn.seekflux</groupId>
    <artifactId>seekflux-agent-runtime-core</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

The Core contains the Loop, Router, Context Engine, Tool execution, cancellation, capability,
checkpoint, durable-wait and side-effect protocols. It deliberately contains no JDBC, Redis,
model-provider, Micrometer or Spring implementation. Hosts provide their own implementations
through the Runtime SPI.

Current development source supports incremental durable message facts and reference-based
recovery. The repository's PostgreSQL adapter commits Assistant/DECIDED and ToolResult/terminal
journal pairs atomically, stores compact Checkpoint references, and restores Session state from
periodic baselines plus later events. Original messages and compaction summaries remain durable;
the model context is a rebuildable projection. Core itself still does not bring this JDBC adapter.
Hosts upgrading their persistence must implement the atomic result overload rather than relying
on its legacy compatibility default. See the [persistence v2 contract](../../contracts/runtime/agent-persistence-v2.md)
and [ADR-017](../../docs/adr/ADR-017-agent-event-facts-and-reference-snapshots.md).

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

## Default MCP client (current development source)

The Core supplies the client and host-side connection manager; MCP servers remain external services.
It currently supports the `2025-11-25` Streamable HTTP Tool subset, not every MCP capability.
To try current source locally, install both modules and use `1.0.0-RC2-SNAPSHOT`:

```bash
mvn -pl platform/agent-runtime,platform/agent-runtime-spring-boot-autoconfigure -am install
```

Plain Java setup (MCP imports are `mcp.model.McpToolPolicy`, `mcp.model.McpServerConfig`,
and `mcp.connection.McpConnectionManager` under `io.seekflux.platform.agentruntime`, alongside
Runtime `AgentTool`, `AgentToolRegistry`, and `AgentToolRegistrationPolicy`):

```java
var policy = new McpToolPolicy("lookup", "policy-v1", AgentTool.Effect.READ_ONLY,
        false, Set.of(), "");
var server = McpServerConfig.streamableHttp("docs", URI.create("https://example.test/mcp"),
        Map.of("lookup", policy));
var registry = new AgentToolRegistry(List.of(), AgentToolRegistrationPolicy.SAFE_ONLY);
try (var connections = new McpConnectionManager(List.of(server), registry)) {
    connections.start();
    // Registry now contains docs__lookup if discovery succeeded; pass it to your Runtime assembly.
    // Keep the manager open for the entire application lifetime, not just discovery.
}
```

Only explicitly listed tools are exposed. Configure local effect/approval/tenant/user policies;
remote annotations do not grant authority. Empty credentials need no implementation; `env:NAME`
resolves a Bearer token at request time. Missing configured secrets fail closed.

Custom transport/client factories, credentials, additional authorization, schema conversion,
business argument/result/receipt/status mapping and event recording can be replaced independently.
Ordinary consumers do not implement these interfaces. See the [public MCP contract](../../contracts/runtime/agent-mcp-v1.md)
and [ADR-016](../../docs/adr/ADR-016-default-mcp-in-runtime-core.md).

MCP packages separate public models and extension contracts from built-in implementations:

```text
mcp/
├── model/                 # Server/tool policy, remote tool, result/event, translated schema
├── spi/                   # Client, credentials, authorization, schema/result/event/call contracts
├── connection/            # Connection manager, lifecycle and per-server isolation
├── exception/             # Controlled protocol/transport errors
└── infrastructure/
    ├── http/              # Default Streamable HTTP client and factory
    ├── auth/              # Environment credential provider
    ├── schema/            # Default bounded Schema translation
    └── tool/              # AgentTool proxy and default result adapter
```

Schema adapters return `mcp.model.McpTranslatedTool`, not a type owned by the default translator.
Tool proxies use `mcp.spi.McpToolCallGateway`, implemented by the connection manager. Package names
changed during unpublished RC2 development; no RC1 MCP API is affected.

The default schema supports bounded string/integer/boolean/string-list fields; nested objects,
number fields and unsupported validation keywords are rejected, not silently dropped. A schema SPI
does not add full JSON Schema support to the Runtime. Resources, Prompts, Sampling, Elicitation,
OAuth, STDIO, persistent server notifications and resumable SSE are not implemented.

Default results preserve `content` and `structuredContent`. `_meta.externalReceipt` and status
`SUCCEEDED`/`FAILED` are optional SeekFlux conventions, not MCP guarantees. Default arguments do
not inject an idempotency key; a business adapter must map it to a mechanism actually supported by
the target system. Mutation recovery still requires a durable ledger and reliable external status
query; unknown outcomes are never replayed merely because a connection recovered.
