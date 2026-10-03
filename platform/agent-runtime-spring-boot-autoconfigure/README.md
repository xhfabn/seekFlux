# SeekFlux Agent Runtime Spring Boot Auto-configuration

This module assembles the provider-neutral Runtime from host-provided Spring beans. It depends on
`seekflux-agent-runtime-core`; its Spring Boot dependencies are optional and therefore are not
transitive to consumers.

Current source is `1.0.0-RC2-SNAPSHOT` (not published), with opt-in default MCP assembly.
The published RC1 example below does not include MCP. For local development install current
modules and select the snapshot version; no public snapshot repository is configured.

```xml
<dependency>
    <groupId>io.github.xhfabn.seekflux</groupId>
    <artifactId>seekflux-agent-runtime-spring-boot-autoconfigure</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

The consuming application must already provide Spring Boot. It must also provide these Runtime
ports:

- `AgentSessionStore`
- `ExecutionAuthorityStore`
- `PromptResolver`
- the `AgentDefinition` and `LlmClient` selection used by its application service

Every `AgentTool` bean is collected into the Tool registry. The auto-configuration supplies the
standard Context Engine, finite-step Runtime, Loop, Feature Pipeline, Session Executor, Router,
bounded executors and safe NOOP implementations for optional recorders.

The default Tool registration policy accepts only `READ_ONLY` and `IDEMPOTENT` Tools. Registering a
`MUTATING` Tool requires an explicit `AgentToolRegistrationPolicy` bean and a durable
`AgentRecoveryStore` whose side-effect ledger is enabled.

Configuration prefix:

```yaml
seekflux:
  agent:
    runtime:
      enabled: true
      execution-threads: 8
      execution-queue-capacity: 64
      max-concurrent-model-calls: 4
      max-concurrent-tool-calls: 8
      cancellation-poll-interval: 100ms
      shutdown-grace-period: 5s
      steer-queue-max-depth: 32
      context:
        max-input-tokens: 8192
        target-input-tokens: 6144
        overflow-input-tokens: 4096
        recent-turns: 2
        compaction-mode: sync
        compaction-timeout: 500ms
        overflow-retry-limit: 1
```

Set `seekflux.agent.runtime.enabled=false` to disable Runtime automatic assembly.
MCP assembly is independently opt-in and requires an existing `AgentToolRegistry`.

The publicly available `1.0.0-RC1` release and subsequent release steps are documented in the
[Agent Runtime release manual](../agent-runtime/RELEASING.md).

## Default MCP assembly (current development source)

```yaml
seekflux:
  agent:
    mcp:
      enabled: true
      servers:
        - id: docs
          endpoint: https://example.test/mcp
          credential-ref: env:DOCS_MCP_TOKEN
          config-version: config-v1
          transport: STREAMABLE_HTTP
          protocol-version: 2025-11-25
          connect-timeout: 2s
          request-timeout: 5s
          max-concurrent-calls: 8
          tools:
            - remote-name: lookup
              policy-version: policy-v1
              effect: READ_ONLY
              approval-required: false
```

No MCP beans or network connections are created by default. Once enabled, the manager starts
discovery and closes with the context. Discovery failure leaves the individual source disconnected;
local tools remain usable. Agent definitions/capability catalogs still decide whether a registered
tool is available to a particular Agent; registering it does not grant every Agent access.

Supply `McpClientFactory`, `McpCredentialProvider`, `McpToolAuthorizer`, `McpToolSchemaAdapter`,
`McpToolResultAdapter`, `McpEventRecorder` or `McpConnectionManager` beans to replace corresponding
defaults. No `ObjectMapper` bean is required. A custom authorizer only adds restrictions to local
allowlists. A custom client may support `CUSTOM` transport or another protocol version; the default
factory rejects unsupported choices. Changing semantics must bump local policy/config versions.

Extension interfaces are in `io.seekflux.platform.agentruntime.mcp.spi`; configuration/result
models are in `mcp.model`, the manager in `mcp.connection`, and defaults in the corresponding
`mcp.infrastructure` subpackages. Spring property names and replacement behavior are unchanged.

Defaults and limitations are defined in the [MCP contract](../../contracts/runtime/agent-mcp-v1.md).
