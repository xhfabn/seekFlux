package io.seekflux.platform.agentruntime.autoconfigure;

import io.seekflux.platform.agentruntime.application.api.Router;
import io.seekflux.platform.agentruntime.application.spi.business.context.ContextEngine;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolRegistrationPolicy;
import io.seekflux.platform.agentruntime.application.spi.business.tool.ToolExecutionPolicy;
import io.seekflux.platform.agentruntime.application.spi.capability.context.ContextCompactionStore;
import io.seekflux.platform.agentruntime.application.spi.capability.context.ContextEventRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.event.AgentRunRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.event.CapabilityEventRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.CancellationSignalStore;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.ExecutionAuthorityStore;
import io.seekflux.platform.agentruntime.application.spi.capability.prompt.PromptResolver;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.AgentToolExecutor;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.ToolExecutionObserver;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityCatalog;
import io.seekflux.platform.agentruntime.domain.model.context.ContextWindowPolicy;
import io.seekflux.platform.agentruntime.domain.service.capability.CapabilityResolver;
import io.seekflux.platform.agentruntime.domain.service.context.DefaultContextEngine;
import io.seekflux.platform.agentruntime.domain.service.execution.AgentCallGuard;
import io.seekflux.platform.agentruntime.domain.service.execution.SessionExecutor;
import io.seekflux.platform.agentruntime.domain.service.execution.SteerQueuePolicy;
import io.seekflux.platform.agentruntime.domain.service.feature.BuiltInFeatureNodes;
import io.seekflux.platform.agentruntime.domain.service.feature.DefaultFeaturePipeline;
import io.seekflux.platform.agentruntime.domain.service.feature.FeaturePipeline;
import io.seekflux.platform.agentruntime.domain.service.loop.AgentLoop;
import io.seekflux.platform.agentruntime.domain.service.loop.DefaultAgentLoop;
import io.seekflux.platform.agentruntime.domain.service.router.DefaultRouter;
import io.seekflux.platform.agentruntime.domain.service.runtime.AgentRuntime;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.infrastructure.tool.DefaultAgentToolExecutor;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(AgentRuntime.class)
@ConditionalOnProperty(
        prefix = "seekflux.agent.runtime",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(AgentRuntimeProperties.class)
public class AgentRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock seekFluxAgentClock() {
        return Clock.systemUTC();
    }

    @Bean(name = "seekFluxAgentExecutionExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "seekFluxAgentExecutionExecutor")
    ExecutorService seekFluxAgentExecutionExecutor(AgentRuntimeProperties properties) {
        properties.validate();
        return boundedExecutor(
                properties.getExecutionThreads(),
                properties.getExecutionQueueCapacity(),
                "seekflux-agent-execution-");
    }

    @Bean(name = "seekFluxAgentContextExecutor", destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "seekFluxAgentContextExecutor")
    ExecutorService seekFluxAgentContextExecutor(AgentRuntimeProperties properties) {
        properties.validate();
        return boundedExecutor(
                properties.getContextThreads(),
                properties.getContextQueueCapacity(),
                "seekflux-agent-context-");
    }

    @Bean(name = "seekFluxAgentRenewalScheduler", destroyMethod = "shutdownNow")
    @ConditionalOnMissingBean(name = "seekFluxAgentRenewalScheduler")
    ScheduledExecutorService seekFluxAgentRenewalScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                namedThreadFactory("seekflux-agent-authority-"));
    }

    @Bean
    @ConditionalOnMissingBean
    AgentToolRegistrationPolicy seekFluxAgentToolRegistrationPolicy() {
        return AgentToolRegistrationPolicy.SAFE_ONLY;
    }

    @Bean
    @ConditionalOnMissingBean
    AgentToolRegistry seekFluxAgentToolRegistry(
            ObjectProvider<AgentTool> tools,
            AgentToolRegistrationPolicy policy) {
        List<AgentTool> ordered = tools.orderedStream()
                .sorted(Comparator.comparing(AgentTool::name))
                .toList();
        return new AgentToolRegistry(ordered, policy);
    }

    @Bean
    @ConditionalOnMissingBean
    AgentToolExecutor seekFluxAgentToolExecutor(AgentToolRegistry registry) {
        return new DefaultAgentToolExecutor(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    CapabilityCatalog seekFluxAgentCapabilityCatalog() {
        return CapabilityCatalog.EMPTY;
    }

    @Bean
    @ConditionalOnMissingBean
    CapabilityEventRecorder seekFluxAgentCapabilityEventRecorder() {
        return CapabilityEventRecorder.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    CapabilityResolver seekFluxAgentCapabilityResolver(
            CapabilityCatalog catalog,
            CapabilityEventRecorder events,
            AgentToolRegistry tools,
            Clock clock) {
        catalog.validateAvailableTools(tools.names());
        return new CapabilityResolver(catalog, events, clock, tools::names);
    }

    @Bean
    @ConditionalOnMissingBean
    ContextCompactionStore seekFluxAgentContextCompactionStore() {
        return ContextCompactionStore.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    ContextEventRecorder seekFluxAgentContextEventRecorder() {
        return ContextEventRecorder.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    AgentRunRecorder seekFluxAgentRunRecorder() {
        return AgentRunRecorder.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    ToolExecutionPolicy seekFluxAgentToolExecutionPolicy() {
        return ToolExecutionPolicy.ALLOW_ALL;
    }

    @Bean
    @ConditionalOnMissingBean
    ToolExecutionObserver seekFluxAgentToolExecutionObserver() {
        return ToolExecutionObserver.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    AgentRecoveryStore seekFluxAgentRecoveryStore() {
        return AgentRecoveryStore.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    CancellationSignalStore seekFluxAgentCancellationSignalStore() {
        return CancellationSignalStore.NOOP;
    }

    @Bean
    @ConditionalOnMissingBean
    ContextEngine seekFluxAgentContextEngine(
            PromptResolver prompts,
            AgentToolRegistry tools,
            ContextCompactionStore compactions,
            ContextEventRecorder events,
            @Qualifier("seekFluxAgentContextExecutor") ExecutorService contextExecutor,
            AgentRuntimeProperties properties,
            Clock clock) {
        AgentRuntimeProperties.Context context = properties.getContext();
        ContextWindowPolicy policy = new ContextWindowPolicy(
                context.getMaxInputTokens(),
                context.getTargetInputTokens(),
                context.getOverflowInputTokens(),
                context.getRecentTurns(),
                context.getCompactionMode(),
                context.getCompactionTimeout(),
                context.getOverflowRetryLimit());
        return new DefaultContextEngine(
                prompts, tools, compactions, events, policy, contextExecutor, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    AgentCallGuard seekFluxAgentCallGuard(AgentRuntimeProperties properties) {
        return new AgentCallGuard(
                properties.getMaxConcurrentModelCalls(),
                properties.getMaxConcurrentToolCalls(),
                AgentCallGuard.FaultInjector.NONE);
    }

    @Bean
    @ConditionalOnMissingBean
    AgentRuntime seekFluxAgentRuntime(
            AgentToolRegistry tools,
            AgentToolExecutor toolExecutor,
            @Qualifier("seekFluxAgentExecutionExecutor") ExecutorService executor,
            AgentRunRecorder recorder,
            Clock clock,
            AgentCallGuard callGuard,
            ToolExecutionPolicy toolPolicy,
            ToolExecutionObserver toolObserver,
            CapabilityResolver capabilityResolver) {
        return new AgentRuntime(
                tools,
                toolExecutor,
                executor,
                recorder,
                clock,
                callGuard,
                toolPolicy,
                toolObserver,
                capabilityResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    AgentLoop seekFluxAgentLoop(
            AgentRuntime runtime,
            ContextEngine contextEngine,
            Clock clock,
            ContextEventRecorder events) {
        return new DefaultAgentLoop(runtime, contextEngine, clock, events);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    SessionExecutor seekFluxAgentSessionExecutor(
            ExecutionAuthorityStore authorityStore,
            AgentSessionStore sessions,
            AgentLoop loop,
            @Qualifier("seekFluxAgentRenewalScheduler") ScheduledExecutorService renewalScheduler,
            Clock clock,
            CancellationSignalStore cancellationSignals,
            AgentRecoveryStore recoveryStore,
            CapabilityResolver capabilityResolver,
            AgentRuntimeProperties properties) {
        return new SessionExecutor(
                authorityStore,
                sessions,
                loop,
                renewalScheduler,
                clock,
                cancellationSignals,
                properties.getCancellationPollInterval(),
                properties.getShutdownGracePeriod(),
                recoveryStore,
                io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryFaultInjector.NONE,
                new SteerQueuePolicy(properties.getSteerQueueMaxDepth()),
                capabilityResolver);
    }

    @Bean
    @ConditionalOnMissingBean
    FeaturePipeline seekFluxAgentFeaturePipeline(
            AgentSessionStore sessions,
            CapabilityResolver capabilities,
            Clock clock) {
        return new DefaultFeaturePipeline(List.of(
                new BuiltInFeatureNodes.SessionLoad(sessions),
                new BuiltInFeatureNodes.AgentResolve(sessions, clock),
                new BuiltInFeatureNodes.CapabilityResolve(capabilities),
                new BuiltInFeatureNodes.ParamInit(),
                new BuiltInFeatureNodes.ResumeEval()));
    }

    @Bean
    @ConditionalOnMissingBean(Router.class)
    Router seekFluxAgentRouter(
            FeaturePipeline featurePipeline,
            AgentSessionStore sessions,
            SessionExecutor sessionExecutor,
            Clock clock) {
        return new DefaultRouter(featurePipeline, sessions, sessionExecutor, clock);
    }

    private static ExecutorService boundedExecutor(
            int threads,
            int queueCapacity,
            String threadPrefix) {
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                namedThreadFactory(threadPrefix),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
    }
}
