# SeekFlux Agent Runtime Spring Boot Auto-configuration

This module assembles the provider-neutral Runtime from host-provided Spring beans. It depends on
`seekflux-agent-runtime-core`; its Spring Boot dependencies are optional and therefore are not
transitive to consumers.

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

Set `seekflux.agent.runtime.enabled=false` to disable all automatic assembly.

Release prerequisites and Central staging steps are documented in the
[Agent Runtime release manual](../agent-runtime/RELEASING.md).
