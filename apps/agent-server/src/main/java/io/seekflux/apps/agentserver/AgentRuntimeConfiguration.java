package io.seekflux.apps.agentserver;

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
import io.seekflux.apps.agentserver.runtime.AgentRuntimeExecutionAdapter;
import io.seekflux.apps.agentserver.runtime.AgentExecutionMetrics;
import io.seekflux.apps.agentserver.runtime.MicrometerAgentExecutionMetrics;
import io.seekflux.apps.agentserver.runtime.AgentSessionGoalAdapter;
import io.seekflux.apps.agentserver.runtime.DeterministicSearchLlmClient;
import io.seekflux.apps.agentserver.runtime.DirectSearchExecutionAdapter;
import io.seekflux.apps.agentserver.runtime.OpenAiCompatibleLlmClient;
import io.seekflux.apps.agentserver.runtime.RedisAgentSessionProjection;
import io.seekflux.apps.agentserver.runtime.RedisExecutionAuthorityStore;
import io.seekflux.apps.agentserver.runtime.RedisCancellationSignalStore;
import io.seekflux.apps.agentserver.runtime.RedisShadowSettingsStore;
import io.seekflux.apps.agentserver.runtime.SearchDirectTool;
import io.seekflux.apps.agentserver.runtime.SearchFilteredTool;
import io.seekflux.platform.agentruntime.AgentDefinition;
import io.seekflux.platform.agentruntime.AgentRunRecorder;
import io.seekflux.platform.agentruntime.AgentCallGuard;
import io.seekflux.platform.agentruntime.AgentRuntime;
import io.seekflux.platform.agentruntime.AgentTool;
import io.seekflux.platform.agentruntime.AgentToolExecutor;
import io.seekflux.platform.agentruntime.AgentToolRegistry;
import io.seekflux.platform.agentruntime.DefaultAgentToolExecutor;
import io.seekflux.platform.agentruntime.context.ContextEngine;
import io.seekflux.platform.agentruntime.context.DefaultContextEngine;
import io.seekflux.platform.agentruntime.context.MapPromptResolver;
import io.seekflux.platform.agentruntime.context.PromptResolver;
import io.seekflux.platform.agentruntime.execution.ExecutionAuthorityStore;
import io.seekflux.platform.agentruntime.execution.CancellationSignalStore;
import io.seekflux.platform.agentruntime.execution.SessionExecutor;
import io.seekflux.platform.agentruntime.feature.BuiltInFeatureNodes;
import io.seekflux.platform.agentruntime.feature.DefaultFeaturePipeline;
import io.seekflux.platform.agentruntime.feature.FeatureNode;
import io.seekflux.platform.agentruntime.feature.FeaturePipeline;
import io.seekflux.platform.agentruntime.llm.LlmClient;
import io.seekflux.platform.agentruntime.llm.AgentShadowRecorder;
import io.seekflux.platform.agentruntime.llm.ShadowControl;
import io.seekflux.platform.agentruntime.llm.ShadowSettingsStore;
import io.seekflux.platform.agentruntime.llm.ShadowingLlmClient;
import io.seekflux.platform.agentruntime.loop.AgentLoop;
import io.seekflux.platform.agentruntime.loop.DefaultAgentLoop;
import io.seekflux.platform.agentruntime.router.DefaultRouter;
import io.seekflux.platform.agentruntime.router.Router;
import io.seekflux.platform.agentruntime.session.AgentSessionStore;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
class AgentRuntimeConfiguration {

    @Bean
    Clock agentClock() {
        return Clock.systemUTC();
    }

    @Bean
    AgentExecutionMetrics agentExecutionMetrics(MeterRegistry meterRegistry) {
        return new MicrometerAgentExecutionMetrics(meterRegistry);
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

    @Bean(name = "seekFluxAgentTools")
    List<AgentTool> seekFluxAgentTools(
            SearchDirectTool searchDirectTool,
            SearchFilteredTool searchFilteredTool) {
        return List.of(searchDirectTool, searchFilteredTool);
    }

    @Bean
    AgentToolRegistry agentToolRegistry(
            @Qualifier("seekFluxAgentTools") List<AgentTool> tools) {
        return new AgentToolRegistry(tools);
    }

    @Bean
    AgentToolExecutor agentToolExecutor(AgentToolRegistry registry) {
        return new DefaultAgentToolExecutor(registry);
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
            AgentToolRegistry agentToolRegistry) {
        return new DefaultContextEngine(agentPromptResolver, agentToolRegistry);
    }

    @Bean
    AgentRuntime finiteStepAgentRuntime(
            AgentToolRegistry tools,
            AgentToolExecutor toolExecutor,
            @Qualifier("agentExecutionExecutor") ExecutorService executor,
            AgentRunRecorder recorder,
            AgentCallGuard agentCallGuard,
            Clock agentClock) {
        return new AgentRuntime(tools, toolExecutor, executor, recorder, agentClock, agentCallGuard);
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
            Clock agentClock) {
        return new DefaultAgentLoop(finiteStepAgentRuntime, agentContextEngine, agentClock);
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
            @Value("${seekflux.agent.cancel.poll-interval-ms:100}") long cancelPollMillis,
            @Value("${seekflux.agent.shutdown-grace-ms:5000}") long shutdownGraceMillis,
            Clock agentClock) {
        return new SessionExecutor(
                authorityStore,
                sessions,
                defaultAgentLoop,
                agentAuthorityRenewalScheduler,
                agentClock,
                cancellationSignalStore,
                Duration.ofMillis(cancelPollMillis),
                Duration.ofMillis(shutdownGraceMillis));
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

    @Bean(name = "agentResumeEvalFeatureNode")
    FeatureNode agentResumeEvalFeatureNode() {
        return new BuiltInFeatureNodes.ResumeEval();
    }

    @Bean
    FeaturePipeline agentFeaturePipeline(
            @Qualifier("agentSessionLoadFeatureNode") FeatureNode sessionLoad,
            @Qualifier("agentResolveFeatureNode") FeatureNode agentResolve,
            @Qualifier("agentParamInitFeatureNode") FeatureNode paramInit,
            @Qualifier("agentResumeEvalFeatureNode") FeatureNode resumeEval) {
        return new DefaultFeaturePipeline(List.of(sessionLoad, agentResolve, paramInit, resumeEval));
    }

    @Bean
    Router agentRouter(
            FeaturePipeline agentFeaturePipeline,
            AgentSessionStore sessions,
            SessionExecutor agentSessionExecutor,
            Clock agentClock) {
        return new DefaultRouter(agentFeaturePipeline, sessions, agentSessionExecutor, agentClock);
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
                    outputPrice);
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
                Set.of(SearchDirectTool.NAME, SearchFilteredTool.NAME),
                maxSteps,
                2,
                Duration.ofMillis(timeoutMillis),
                true);
    }
}
