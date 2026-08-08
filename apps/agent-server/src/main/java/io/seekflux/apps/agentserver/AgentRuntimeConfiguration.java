package io.seekflux.apps.agentserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.agent.application.AgentSearchApplicationService;
import io.seekflux.agent.domain.SearchClarificationPolicy;
import io.seekflux.agent.port.in.AgentSearchUseCase;
import io.seekflux.agent.port.out.AgentExecutionPort;
import io.seekflux.apps.agentserver.runtime.AgentRuntimeExecutionAdapter;
import io.seekflux.apps.agentserver.runtime.DeterministicSearchLlmClient;
import io.seekflux.apps.agentserver.runtime.RedisAgentSessionProjection;
import io.seekflux.apps.agentserver.runtime.RedisExecutionAuthorityStore;
import io.seekflux.apps.agentserver.runtime.SearchDirectTool;
import io.seekflux.platform.agentruntime.AgentDefinition;
import io.seekflux.platform.agentruntime.AgentRunRecorder;
import io.seekflux.platform.agentruntime.AgentRuntime;
import io.seekflux.platform.agentruntime.AgentTool;
import io.seekflux.platform.agentruntime.AgentToolExecutor;
import io.seekflux.platform.agentruntime.AgentToolRegistry;
import io.seekflux.platform.agentruntime.DefaultAgentToolExecutor;
import io.seekflux.platform.agentruntime.context.ContextEngine;
import io.seekflux.platform.agentruntime.context.DefaultContextEngine;
import io.seekflux.platform.agentruntime.execution.ExecutionAuthorityStore;
import io.seekflux.platform.agentruntime.execution.SessionExecutor;
import io.seekflux.platform.agentruntime.feature.BuiltInFeatureNodes;
import io.seekflux.platform.agentruntime.feature.DefaultFeaturePipeline;
import io.seekflux.platform.agentruntime.feature.FeatureNode;
import io.seekflux.platform.agentruntime.feature.FeaturePipeline;
import io.seekflux.platform.agentruntime.llm.LlmClient;
import io.seekflux.platform.agentruntime.loop.AgentLoop;
import io.seekflux.platform.agentruntime.loop.DefaultAgentLoop;
import io.seekflux.platform.agentruntime.router.DefaultRouter;
import io.seekflux.platform.agentruntime.router.Router;
import io.seekflux.platform.agentruntime.session.AgentSessionStore;
import io.seekflux.search.port.in.SearchUseCase;
import java.time.Clock;
import java.time.Duration;
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

@Configuration
class AgentRuntimeConfiguration {

    @Bean
    Clock agentClock() {
        return Clock.systemUTC();
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

    @Bean
    SearchClarificationPolicy searchClarificationPolicy() {
        return new SearchClarificationPolicy();
    }

    @Bean
    SearchDirectTool searchDirectTool(SearchUseCase directSearchUseCase) {
        return new SearchDirectTool(directSearchUseCase);
    }

    @Bean(name = "seekFluxAgentTools")
    List<AgentTool> seekFluxAgentTools(SearchDirectTool searchDirectTool) {
        return List.of(searchDirectTool);
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
    ContextEngine agentContextEngine() {
        return new DefaultContextEngine();
    }

    @Bean
    AgentRuntime finiteStepAgentRuntime(
            AgentToolRegistry tools,
            AgentToolExecutor toolExecutor,
            @Qualifier("agentExecutionExecutor") ExecutorService executor,
            AgentRunRecorder recorder,
            Clock agentClock) {
        return new AgentRuntime(tools, toolExecutor, executor, recorder, agentClock);
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
    SessionExecutor agentSessionExecutor(
            ExecutionAuthorityStore authorityStore,
            AgentSessionStore sessions,
            AgentLoop defaultAgentLoop,
            ScheduledExecutorService agentAuthorityRenewalScheduler,
            Clock agentClock) {
        return new SessionExecutor(
                authorityStore,
                sessions,
                defaultAgentLoop,
                agentAuthorityRenewalScheduler,
                agentClock);
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
            @Value("${seekflux.agent.timeout-ms:2500}") long timeoutMillis) {
        AgentDefinition assistant = definition(
                "search-assistant",
                "search-assistant-v1",
                "search-agent-prompt-v1",
                3,
                timeoutMillis);
        AgentDefinition precise = definition(
                "search-precise",
                "search-precise-v1",
                "search-precise-prompt-v1",
                2,
                timeoutMillis);
        return Map.of(assistant.id(), assistant, precise.id(), precise);
    }

    @Bean(name = "seekFluxAgentLlmClients")
    Map<String, LlmClient> seekFluxAgentLlmClients(SearchClarificationPolicy clarificationPolicy) {
        LlmClient client = new DeterministicSearchLlmClient(clarificationPolicy);
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
            RedisAgentSessionProjection projection) {
        return new AgentRuntimeExecutionAdapter(
                agentRouter,
                definitions,
                llmClients,
                directSearchUseCase,
                projection);
    }

    @Bean
    AgentSearchUseCase agentSearchUseCase(AgentExecutionPort executionPort) {
        return new AgentSearchApplicationService(executionPort);
    }

    private static AgentDefinition definition(
            String id,
            String version,
            String promptVersion,
            int maxSteps,
            long timeoutMillis) {
        return new AgentDefinition(
                id,
                version,
                "default-react-loop-v1",
                promptVersion,
                DeterministicSearchLlmClient.VERSION,
                Set.of(SearchDirectTool.NAME),
                maxSteps,
                1,
                Duration.ofMillis(timeoutMillis),
                true);
    }
}
