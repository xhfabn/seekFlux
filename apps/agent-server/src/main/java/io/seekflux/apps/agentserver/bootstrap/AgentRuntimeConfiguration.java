package io.seekflux.apps.agentserver.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.agent.application.AgentSearchApplicationService;
import io.seekflux.agent.domain.SearchClarificationPolicy;
import io.seekflux.agent.domain.QueryModeRouter;
import io.seekflux.agent.domain.SearchIntentAnalyzer;
import io.seekflux.agent.domain.SearchToolPolicy;
import io.seekflux.agent.port.in.AgentSearchUseCase;
import io.seekflux.agent.port.out.AgentConversationPort;
import io.seekflux.agent.port.out.DirectSearchPort;
import io.seekflux.agent.port.out.AgentExecutionPort;
import io.seekflux.agent.infrastructure.llm.DeterministicSearchLlmClient;
import io.seekflux.agent.infrastructure.llm.openai.OpenAiCompatibleLlmClient;
import io.seekflux.agent.infrastructure.observability.AgentExecutionMetrics;
import io.seekflux.agent.infrastructure.observability.MicrometerAgentExecutionMetrics;
import io.seekflux.agent.infrastructure.observability.MicrometerToolExecutionObserver;
import io.seekflux.agent.infrastructure.observability.MicrometerContextEventRecorder;
import io.seekflux.agent.infrastructure.observability.MicrometerCapabilityEventRecorder;
import io.seekflux.agent.infrastructure.event.RedisPushEventRelay;
import io.seekflux.agent.infrastructure.projection.RedisAgentSessionProjection;
import io.seekflux.agent.infrastructure.runtime.AgentRuntimeExecutionAdapter;
import io.seekflux.agent.infrastructure.search.DirectSearchExecutionAdapter;
import io.seekflux.agent.infrastructure.session.AgentSessionGoalAdapter;
import io.seekflux.agent.infrastructure.tool.SearchDirectTool;
import io.seekflux.agent.infrastructure.tool.SearchFilteredTool;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.application.spi.capability.event.AgentRunRecorder;
import io.seekflux.platform.agentruntime.domain.service.execution.AgentCallGuard;
import io.seekflux.platform.agentruntime.domain.service.runtime.AgentRuntime;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolRegistrationPolicy;
import io.seekflux.platform.agentruntime.application.spi.business.tool.ToolExecutionPolicy;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.ToolExecutionObserver;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.AgentToolExecutor;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.infrastructure.tool.DefaultAgentToolExecutor;
import io.seekflux.platform.agentruntime.infrastructure.tool.SwitchToolGroupsTool;
import io.seekflux.platform.agentruntime.application.spi.business.context.ContextEngine;
import io.seekflux.platform.agentruntime.domain.service.context.DefaultContextEngine;
import io.seekflux.platform.agentruntime.infrastructure.prompt.MapPromptResolver;
import io.seekflux.platform.agentruntime.application.spi.capability.prompt.PromptResolver;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.ExecutionAuthorityStore;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.CancellationSignalStore;
import io.seekflux.platform.agentruntime.domain.service.execution.SessionExecutor;
import io.seekflux.platform.agentruntime.domain.service.execution.SteerQueuePolicy;
import io.seekflux.platform.agentruntime.domain.service.feature.BuiltInFeatureNodes;
import io.seekflux.platform.agentruntime.domain.service.feature.DefaultFeaturePipeline;
import io.seekflux.platform.agentruntime.application.spi.business.feature.FeatureNode;
import io.seekflux.platform.agentruntime.domain.service.feature.FeaturePipeline;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.LlmClient;
import io.seekflux.platform.agentruntime.application.spi.capability.shadow.AgentShadowRecorder;
import io.seekflux.platform.agentruntime.domain.service.shadow.ShadowControl;
import io.seekflux.platform.agentruntime.application.spi.capability.shadow.ShadowSettingsStore;
import io.seekflux.platform.agentruntime.infrastructure.llm.ShadowingLlmClient;
import io.seekflux.platform.agentruntime.infrastructure.redis.RedisCancellationSignalStore;
import io.seekflux.platform.agentruntime.infrastructure.redis.RedisExecutionAuthorityStore;
import io.seekflux.platform.agentruntime.infrastructure.redis.RedisShadowSettingsStore;
import io.seekflux.platform.agentruntime.domain.service.loop.AgentLoop;
import io.seekflux.platform.agentruntime.domain.service.loop.DefaultAgentLoop;
import io.seekflux.platform.agentruntime.domain.service.router.DefaultRouter;
import io.seekflux.platform.agentruntime.application.api.Router;
import io.seekflux.platform.agentruntime.application.api.WaitResumeDispatcher;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.command.FeatureRequest;
import io.seekflux.platform.agentruntime.domain.service.wait.WaitTimeoutProcessor;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.application.spi.capability.context.ContextCompactionStore;
import io.seekflux.platform.agentruntime.application.spi.capability.context.ContextEventRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventRelay;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventStream;
import io.seekflux.platform.agentruntime.infrastructure.event.DefaultPushEventStream;
import io.seekflux.platform.agentruntime.application.spi.business.output.OutputGuardPolicy;
import io.seekflux.platform.agentruntime.domain.model.context.ContextCompactionMode;
import io.seekflux.platform.agentruntime.domain.model.context.ContextWindowPolicy;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityCatalog;
import io.seekflux.platform.agentruntime.domain.model.capability.SkillDefinition;
import io.seekflux.platform.agentruntime.domain.model.capability.ToolGroupDefinition;
import io.seekflux.platform.agentruntime.domain.service.capability.CapabilityResolver;
import io.seekflux.search.port.in.SearchUseCase;
import java.time.Clock;
import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@EnableScheduling
class AgentRuntimeConfiguration {

    @Bean
    Clock agentClock() {
        return Clock.systemUTC();
    }

    @Bean
    AgentExecutionMetrics agentExecutionMetrics(MeterRegistry meterRegistry) {
        return new MicrometerAgentExecutionMetrics(meterRegistry);
    }

    @Bean
    ContextEventRecorder contextEventRecorder(MeterRegistry meterRegistry) {
        return new MicrometerContextEventRecorder(meterRegistry);
    }

    @Bean(name = "agentExecutionExecutor", destroyMethod = "shutdown")
    ExecutorService agentExecutionExecutor(
            @Value("${seekflux.agent.execution-pool.core-size:2}") int coreSize,
            @Value("${seekflux.agent.execution-pool.max-size:4}") int maxSize,
            @Value("${seekflux.agent.execution-pool.queue-capacity:50}") int queueCapacity) {
        return AgentSearchConfiguration.boundedExecutor(
                "seekflux-agent-step-", coreSize, maxSize, queueCapacity);
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService agentAuthorityRenewalScheduler() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "seekflux-agent-authority-renewal");
            thread.setDaemon(false);
            return thread;
        });
    }

    @Bean(name = "agentShadowExecutor", destroyMethod = "shutdown")
    ExecutorService agentShadowExecutor(
            @Value("${seekflux.agent.shadow.queue-capacity:20}") int queueCapacity) {
        return AgentSearchConfiguration.boundedExecutor(
                "seekflux-agent-shadow-", 1, 1, queueCapacity);
    }

    @Bean(name = "agentContextCompactionExecutor", destroyMethod = "shutdown")
    ExecutorService agentContextCompactionExecutor(
            @Value("${seekflux.agent.context.async-queue-capacity:20}") int queueCapacity) {
        return AgentSearchConfiguration.boundedExecutor(
                "seekflux-agent-context-", 1, 1, queueCapacity);
    }

    @Bean(name = "agentPushRelayExecutor", destroyMethod = "shutdown")
    ExecutorService agentPushRelayExecutor(
            @Value("${seekflux.agent.push.relay-queue-capacity:100}") int queueCapacity) {
        return AgentSearchConfiguration.boundedExecutor(
                "seekflux-agent-push-relay-", 1, 1, queueCapacity);
    }

    @Bean(name = "agentWaitTimeoutExecutor", destroyMethod = "shutdown")
    ExecutorService agentWaitTimeoutExecutor() {
        return AgentSearchConfiguration.boundedExecutor(
                "seekflux-agent-wait-timeout-", 1, 1, 1);
    }

    @Bean(name = "agentSseExecutor", destroyMethod = "shutdown")
    ExecutorService agentSseExecutor(
            @Value("${seekflux.agent.push.sse-max-concurrency:8}") int maxConcurrency,
            @Value("${seekflux.agent.push.sse-queue-capacity:100}") int queueCapacity) {
        return AgentSearchConfiguration.boundedExecutor(
                "seekflux-agent-sse-", 2, Math.max(2, maxConcurrency), queueCapacity);
    }

    @Bean(destroyMethod = "close")
    PushEventRelay agentPushEventRelay(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Qualifier("agentPushRelayExecutor") ExecutorService relayExecutor,
            @Value("${seekflux.agent.push.redis-channel:seekflux:agent:push:v1}") String channel,
            @Value("${seekflux.agent.push.sequence-key-prefix:seekflux:agent:push:seq:}")
                    String sequenceKeyPrefix) {
        return new RedisPushEventRelay(
                connectionFactory, redis, objectMapper, relayExecutor,
                channel, sequenceKeyPrefix);
    }

    @Bean(destroyMethod = "close")
    PushEventStream agentPushEventStream(
            PushEventRelay agentPushEventRelay,
            Clock agentClock,
            @Value("${seekflux.agent.push.history-capacity:256}") int historyCapacity,
            @Value("${seekflux.agent.push.subscriber-capacity:64}") int subscriberCapacity,
            @Value("${seekflux.agent.push.max-sessions:1024}") int maxSessions) {
        return new DefaultPushEventStream(
                historyCapacity,
                subscriberCapacity,
                maxSessions,
                java.util.UUID.randomUUID().toString(),
                agentPushEventRelay,
                agentClock);
    }

    @Bean
    ShadowControl agentShadowControl(
            @Value("${seekflux.agent.shadow.enabled:false}") boolean enabled,
            @Value("${seekflux.agent.shadow.sample-rate:0.0}") double sampleRate,
            ShadowSettingsStore shadowSettingsStore) {
        return new ShadowControl(enabled, sampleRate, shadowSettingsStore);
    }

    @Bean
    ShadowSettingsStore shadowSettingsStore(StringRedisTemplate redis) {
        return new RedisShadowSettingsStore(redis);
    }

    @Bean
    SearchClarificationPolicy searchClarificationPolicy() {
        return new SearchClarificationPolicy();
    }

    @Bean
    QueryModeRouter queryModeRouter() {
        return new QueryModeRouter();
    }

    @Bean
    SearchIntentAnalyzer searchIntentAnalyzer() {
        return new SearchIntentAnalyzer();
    }

    @Bean
    SearchToolPolicy searchToolPolicy() {
        return new SearchToolPolicy();
    }

    @Bean
    SearchDirectTool searchDirectTool(SearchUseCase directSearchUseCase) {
        return new SearchDirectTool(directSearchUseCase);
    }

    @Bean
    SearchFilteredTool searchFilteredTool(SearchUseCase directSearchUseCase) {
        return new SearchFilteredTool(directSearchUseCase);
    }

    @Bean
    SwitchToolGroupsTool switchToolGroupsTool(CapabilityCatalog agentCapabilityCatalog) {
        return new SwitchToolGroupsTool(agentCapabilityCatalog);
    }

    @Bean(name = "seekFluxAgentTools")
    List<AgentTool> seekFluxAgentTools(
            SearchDirectTool searchDirectTool,
            SearchFilteredTool searchFilteredTool,
            SwitchToolGroupsTool switchToolGroupsTool) {
        return List.of(searchDirectTool, searchFilteredTool, switchToolGroupsTool);
    }

    @Bean
    AgentToolRegistry agentToolRegistry(
            @Qualifier("seekFluxAgentTools") List<AgentTool> tools,
            AgentToolRegistrationPolicy agentToolRegistrationPolicy) {
        return new AgentToolRegistry(tools, agentToolRegistrationPolicy);
    }

    @Bean
    AgentToolRegistrationPolicy agentToolRegistrationPolicy() {
        return AgentToolRegistrationPolicy.SAFE_ONLY;
    }

    @Bean
    ToolExecutionPolicy toolExecutionPolicy() {
        return ToolExecutionPolicy.ALLOW_ALL;
    }

    @Bean
    ToolExecutionObserver toolExecutionObserver(MeterRegistry meterRegistry) {
        return new MicrometerToolExecutionObserver(meterRegistry);
    }

    @Bean
    AgentToolExecutor agentToolExecutor(AgentToolRegistry registry) {
        return new DefaultAgentToolExecutor(registry);
    }

    @Bean
    CapabilityCatalog agentCapabilityCatalog() {
        SkillDefinition broad = new SkillDefinition(
                "search-broad",
                "search-broad-v1",
                SkillDefinition.Type.PROMPT,
                "宽泛探索时优先使用 search_direct，保留 Direct Search 的原始排序与降级语义。",
                "宽泛搜索能力",
                Set.of(SearchDirectTool.NAME),
                Set.of("search-broad-tools"),
                true);
        SkillDefinition precise = new SkillDefinition(
                "search-precise",
                "search-precise-v1",
                SkillDefinition.Type.PROMPT,
                "存在明确标签或结构化约束时使用 search_filtered，不得编造未提供的约束。",
                "结构化精确搜索能力",
                Set.of(SearchFilteredTool.NAME),
                Set.of("search-precise-tools"),
                true);
        return CapabilityCatalog.of(
                "seekflux-search-capabilities-v1",
                List.of(broad, precise),
                List.of(
                        new ToolGroupDefinition(
                                "search-broad-tools", "search-broad-tools-v1",
                                "宽泛召回工具", Set.of(SearchDirectTool.NAME), false),
                        new ToolGroupDefinition(
                                "search-precise-tools", "search-precise-tools-v1",
                                "结构化过滤工具", Set.of(SearchFilteredTool.NAME), false),
                        new ToolGroupDefinition(
                                "capability-control", "capability-control-v1",
                                "只影响下一模型轮的能力切换控制工具",
                                Set.of(SwitchToolGroupsTool.NAME), true)),
                Set.of("search-broad-tools", "search-precise-tools"));
    }

    @Bean
    CapabilityResolver agentCapabilityResolver(
            CapabilityCatalog agentCapabilityCatalog,
            AgentToolRegistry agentToolRegistry,
            MeterRegistry meterRegistry,
            Clock agentClock) {
        agentCapabilityCatalog.validateAvailableTools(agentToolRegistry.names());
        return new CapabilityResolver(
                agentCapabilityCatalog,
                new MicrometerCapabilityEventRecorder(meterRegistry),
                agentClock);
    }

    @Bean
    PromptResolver agentPromptResolver() {
        return new MapPromptResolver(Map.of(
                "search-agent-prompt-v2", """
                        你是 SeekFlux 复杂搜索规划器。只能输出 JSON，不得输出说明文字。
                        允许动作：call_tool、call_tools、complete、clarify、fallback。
                        call_tools 格式为 {"action":"call_tools","calls":[{"tool":"工具名","arguments":{}}]}。
                        观察 Tool 结果后，complete 只返回 {"action":"complete","output":{"selectedTool":"工具名"}}，
                        Runtime 会按引用复用真实候选，禁止复制、编造或重排候选内容。
                        只能调用请求上下文允许的 Tool；复杂查询优先并行调用宽搜与精确过滤，
                        得到 Tool 观察后复用成功候选，不虚构结果，不自行改写 Search 排序。
                        """,
                "search-precise-prompt-v2", """
                        你是 SeekFlux 精确搜索规划器。只能输出结构化 JSON Decision。
                        严格遵守动态工具集、参数 Schema、共同 Deadline 和已有 SearchGoal，
                        Tool 成功后只用 complete.output.selectedTool 引用一个真实候选集，
                        缺少必要目标时追问；Tool 失败时返回 fallback，不生成虚构内容。
                        """));
    }

    @Bean
    ContextEngine agentContextEngine(
            PromptResolver agentPromptResolver,
            AgentToolRegistry agentToolRegistry,
            ContextCompactionStore contextCompactionStore,
            ContextEventRecorder contextEventRecorder,
            @Qualifier("agentContextCompactionExecutor") ExecutorService contextExecutor,
            Clock agentClock,
            @Value("${seekflux.agent.context.max-input-tokens:8192}") int maxTokens,
            @Value("${seekflux.agent.context.target-input-tokens:6144}") int targetTokens,
            @Value("${seekflux.agent.context.overflow-input-tokens:4096}") int overflowTokens,
            @Value("${seekflux.agent.context.recent-turns:2}") int recentTurns,
            @Value("${seekflux.agent.context.compaction-mode:SYNC}") String compactionMode,
            @Value("${seekflux.agent.context.compaction-timeout-ms:500}") long compactionTimeoutMillis,
            @Value("${seekflux.agent.context.overflow-retry-limit:1}") int overflowRetryLimit) {
        ContextWindowPolicy policy = new ContextWindowPolicy(
                maxTokens,
                targetTokens,
                overflowTokens,
                recentTurns,
                ContextCompactionMode.valueOf(compactionMode.trim().toUpperCase(java.util.Locale.ROOT)),
                Duration.ofMillis(compactionTimeoutMillis),
                overflowRetryLimit);
        return new DefaultContextEngine(
                agentPromptResolver,
                agentToolRegistry,
                contextCompactionStore,
                contextEventRecorder,
                policy,
                contextExecutor,
                agentClock);
    }

    @Bean
    AgentRuntime finiteStepAgentRuntime(
            AgentToolRegistry tools,
            AgentToolExecutor toolExecutor,
            @Qualifier("agentExecutionExecutor") ExecutorService executor,
            AgentRunRecorder recorder,
            AgentCallGuard agentCallGuard,
            ToolExecutionPolicy toolExecutionPolicy,
            ToolExecutionObserver toolExecutionObserver,
            CapabilityResolver agentCapabilityResolver,
            Clock agentClock) {
        return new AgentRuntime(
                tools, toolExecutor, executor, recorder, agentClock, agentCallGuard,
                toolExecutionPolicy, toolExecutionObserver, agentCapabilityResolver);
    }

    @Bean
    AgentCallGuard agentCallGuard(
            @Value("${seekflux.agent.bulkhead.max-concurrent-model-calls:4}") int modelCalls,
            @Value("${seekflux.agent.bulkhead.max-concurrent-tool-calls:8}") int toolCalls) {
        return new AgentCallGuard(modelCalls, toolCalls, AgentCallGuard.FaultInjector.NONE);
    }

    @Bean
    AgentLoop defaultAgentLoop(
            AgentRuntime finiteStepAgentRuntime,
            ContextEngine agentContextEngine,
            Clock agentClock,
            ContextEventRecorder contextEventRecorder) {
        return new DefaultAgentLoop(
                finiteStepAgentRuntime, agentContextEngine, agentClock, contextEventRecorder);
    }

    @Bean
    ExecutionAuthorityStore executionAuthorityStore(StringRedisTemplate redis) {
        return new RedisExecutionAuthorityStore(redis);
    }

    @Bean
    CancellationSignalStore cancellationSignalStore(
            StringRedisTemplate redis,
            @Value("${seekflux.agent.cancel.signal-ttl-seconds:30}") long ttlSeconds) {
        return new RedisCancellationSignalStore(redis, Duration.ofSeconds(ttlSeconds));
    }

    @Bean
    SessionExecutor agentSessionExecutor(
            ExecutionAuthorityStore authorityStore,
            AgentSessionStore sessions,
            AgentLoop defaultAgentLoop,
            ScheduledExecutorService agentAuthorityRenewalScheduler,
            CancellationSignalStore cancellationSignalStore,
            AgentRecoveryStore agentRecoveryStore,
            @Value("${seekflux.agent.cancel.poll-interval-ms:100}") long cancelPollMillis,
            @Value("${seekflux.agent.shutdown-grace-ms:5000}") long shutdownGraceMillis,
            @Value("${seekflux.agent.steer.queue-max-depth:32}") int steerQueueMaxDepth,
            CapabilityResolver agentCapabilityResolver,
            Clock agentClock) {
        return new SessionExecutor(
                authorityStore,
                sessions,
                defaultAgentLoop,
                agentAuthorityRenewalScheduler,
                agentClock,
                cancellationSignalStore,
                Duration.ofMillis(cancelPollMillis),
                Duration.ofMillis(shutdownGraceMillis),
                agentRecoveryStore,
                io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryFaultInjector.NONE,
                new SteerQueuePolicy(steerQueueMaxDepth),
                agentCapabilityResolver);
    }

    @Bean(name = "agentSessionLoadFeatureNode")
    FeatureNode agentSessionLoadFeatureNode(AgentSessionStore sessions) {
        return new BuiltInFeatureNodes.SessionLoad(sessions);
    }

    @Bean(name = "agentResolveFeatureNode")
    FeatureNode agentResolveFeatureNode(AgentSessionStore sessions, Clock agentClock) {
        return new BuiltInFeatureNodes.AgentResolve(sessions, agentClock);
    }

    @Bean(name = "agentParamInitFeatureNode")
    FeatureNode agentParamInitFeatureNode() {
        return new BuiltInFeatureNodes.ParamInit();
    }

    @Bean(name = "agentCapabilityResolveFeatureNode")
    FeatureNode agentCapabilityResolveFeatureNode(CapabilityResolver agentCapabilityResolver) {
        return new BuiltInFeatureNodes.CapabilityResolve(agentCapabilityResolver);
    }

    @Bean(name = "agentResumeEvalFeatureNode")
    FeatureNode agentResumeEvalFeatureNode() {
        return new BuiltInFeatureNodes.ResumeEval();
    }

    @Bean
    FeaturePipeline agentFeaturePipeline(
            @Qualifier("agentSessionLoadFeatureNode") FeatureNode sessionLoad,
            @Qualifier("agentResolveFeatureNode") FeatureNode agentResolve,
            @Qualifier("agentCapabilityResolveFeatureNode") FeatureNode capabilityResolve,
            @Qualifier("agentParamInitFeatureNode") FeatureNode paramInit,
            @Qualifier("agentResumeEvalFeatureNode") FeatureNode resumeEval) {
        return new DefaultFeaturePipeline(List.of(
                sessionLoad, agentResolve, capabilityResolve, paramInit, resumeEval));
    }

    @Bean
    Router agentRouter(
            FeaturePipeline agentFeaturePipeline,
            AgentSessionStore sessions,
            SessionExecutor agentSessionExecutor,
            Clock agentClock) {
        return new DefaultRouter(agentFeaturePipeline, sessions, agentSessionExecutor, agentClock);
    }

    @Bean
    WaitResumeDispatcher agentWaitResumeDispatcher(
            Router agentRouter,
            AgentSessionStore sessions,
            @Qualifier("seekFluxAgentDefinitions") Map<String, AgentDefinition> definitions,
            @Qualifier("seekFluxAgentLlmClients") Map<String, LlmClient> llmClients) {
        return (resolution, publisher) -> {
            var session = sessions.restoreFresh(resolution.sessionId())
                    .orElseThrow(() -> new IllegalStateException("wait session does not exist"));
            AgentDefinition definition = definitions.get(session.agentId());
            LlmClient llmClient = llmClients.get(session.agentId());
            if (definition == null || llmClient == null) {
                throw new IllegalStateException("wait Agent definition is unavailable");
            }
            AgentRunRequest runRequest = new AgentRunRequest(
                    resolution.requestId(),
                    resolution.sessionId(),
                    resolution.turnId(),
                    "internal wait resume",
                    Map.of("internalIngress", true));
            return agentRouter.resume(
                    resolution,
                    new FeatureRequest(definition, runRequest, llmClient),
                    publisher);
        };
    }

    @Bean
    WaitTimeoutProcessor agentWaitTimeoutProcessor(
            AgentRecoveryStore waits,
            WaitResumeDispatcher dispatcher,
            Clock agentClock) {
        return new WaitTimeoutProcessor(waits, dispatcher, agentClock);
    }

    @Bean
    AgentWaitTimeoutWorker agentWaitTimeoutWorker(
            WaitTimeoutProcessor processor,
            @Qualifier("agentWaitTimeoutExecutor") ExecutorService executor,
            @Value("${seekflux.agent.wait.timeout-batch-size:32}") int batchSize) {
        return new AgentWaitTimeoutWorker(processor, batchSize, executor);
    }

    @Bean(name = "seekFluxAgentDefinitions")
    Map<String, AgentDefinition> seekFluxAgentDefinitions(
            @Qualifier("seekFluxAgentLlmClients") Map<String, LlmClient> llmClients,
            @Value("${seekflux.agent.timeout-ms:2500}") long timeoutMillis) {
        AgentDefinition assistant = definition(
                "search-assistant",
                "search-assistant-v2",
                "search-agent-prompt-v2",
                llmClients.get("search-assistant").version(),
                4,
                timeoutMillis);
        AgentDefinition precise = definition(
                "search-precise",
                "search-precise-v2",
                "search-precise-prompt-v2",
                llmClients.get("search-precise").version(),
                3,
                timeoutMillis);
        return Map.of(assistant.id(), assistant, precise.id(), precise);
    }

    @Bean(name = "seekFluxAgentLlmClients")
    Map<String, LlmClient> seekFluxAgentLlmClients(
            SearchClarificationPolicy clarificationPolicy,
            ObjectMapper objectMapper,
            @Value("${seekflux.agent.llm.provider:deterministic}") String provider,
            @Value("${seekflux.agent.llm.endpoint:}") String endpoint,
            @Value("${seekflux.agent.llm.api-key:}") String apiKey,
            @Value("${seekflux.agent.llm.model:gpt-4.1-mini}") String model,
            @Value("${seekflux.agent.llm.timeout-ms:1800}") long timeoutMillis,
            @Value("${seekflux.agent.llm.input-usd-per-million-tokens:0}") double inputPrice,
            @Value("${seekflux.agent.llm.output-usd-per-million-tokens:0}") double outputPrice,
            @Value("${seekflux.agent.llm.output-guard.max-repair-attempts:1}") int outputRepairAttempts,
            @Value("${seekflux.agent.llm.output-guard.exhausted-action:DEGRADE}") String outputExhaustedAction,
            ContextEventRecorder contextEventRecorder,
            ShadowControl agentShadowControl,
            @Qualifier("agentShadowExecutor") ExecutorService shadowExecutor,
            AgentShadowRecorder shadowRecorder,
            Clock agentClock,
            @Value("${seekflux.agent.shadow.candidate-version:deterministic-shadow-v1}") String shadowVersion) {
        LlmClient primary;
        if ("openai-compatible".equalsIgnoreCase(provider.trim())) {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("Agent LLM endpoint is required for openai-compatible provider");
            }
            primary = new OpenAiCompatibleLlmClient(
                    HttpClient.newBuilder()
                            .connectTimeout(Duration.ofMillis(timeoutMillis))
                            .build(),
                    objectMapper,
                    URI.create(endpoint.trim()),
                    apiKey,
                    model,
                    Duration.ofMillis(timeoutMillis),
                    inputPrice,
                    outputPrice,
                    new OutputGuardPolicy(
                            outputRepairAttempts,
                            OutputGuardPolicy.ExhaustedAction.valueOf(
                                    outputExhaustedAction.trim().toUpperCase(java.util.Locale.ROOT))),
                    contextEventRecorder,
                    agentClock);
        } else if ("deterministic".equalsIgnoreCase(provider.trim())) {
            primary = new DeterministicSearchLlmClient(clarificationPolicy);
        } else {
            throw new IllegalArgumentException("unsupported Agent LLM provider: " + provider);
        }
        LlmClient client = new ShadowingLlmClient(
                primary,
                new DeterministicSearchLlmClient(clarificationPolicy),
                shadowVersion,
                agentShadowControl,
                shadowExecutor,
                shadowRecorder,
                agentClock);
        return Map.of("search-assistant", client, "search-precise", client);
    }

    @Bean
    RedisAgentSessionProjection redisAgentSessionProjection(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${seekflux.agent.session-projection-ttl-hours:24}") long ttlHours) {
        return new RedisAgentSessionProjection(redis, objectMapper, Duration.ofHours(ttlHours));
    }

    @Bean
    AgentExecutionPort agentExecutionPort(
            Router agentRouter,
            @Qualifier("seekFluxAgentDefinitions") Map<String, AgentDefinition> definitions,
            @Qualifier("seekFluxAgentLlmClients") Map<String, LlmClient> llmClients,
            SearchUseCase directSearchUseCase,
            RedisAgentSessionProjection projection,
            AgentExecutionMetrics agentExecutionMetrics) {
        return new AgentRuntimeExecutionAdapter(
                agentRouter,
                definitions,
                llmClients,
                directSearchUseCase,
                projection,
                agentExecutionMetrics);
    }

    @Bean
    AgentConversationPort agentConversationPort(AgentSessionStore sessions) {
        return new AgentSessionGoalAdapter(sessions);
    }

    @Bean
    DirectSearchPort directSearchPort(SearchUseCase directSearchUseCase) {
        return new DirectSearchExecutionAdapter(directSearchUseCase);
    }

    @Bean
    AgentSearchUseCase agentSearchUseCase(
            AgentExecutionPort executionPort,
            DirectSearchPort directSearchPort,
            AgentConversationPort agentConversationPort,
            QueryModeRouter queryModeRouter,
            SearchIntentAnalyzer searchIntentAnalyzer,
            SearchToolPolicy searchToolPolicy) {
        return new AgentSearchApplicationService(
                executionPort,
                directSearchPort,
                agentConversationPort,
                queryModeRouter,
                searchIntentAnalyzer,
                searchToolPolicy);
    }

    private static AgentDefinition definition(
            String id,
            String version,
            String promptVersion,
            String decisionProviderVersion,
            int maxSteps,
            long timeoutMillis) {
        return new AgentDefinition(
                id,
                version,
                "default-react-loop-v1",
                promptVersion,
                decisionProviderVersion,
                Set.of(
                        SearchDirectTool.NAME,
                        SearchFilteredTool.NAME,
                        SwitchToolGroupsTool.NAME),
                Set.of("search-broad", "search-precise"),
                maxSteps,
                2,
                Duration.ofMillis(timeoutMillis),
                true);
    }
}
