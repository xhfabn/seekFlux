package io.seekflux.platform.agentruntime.autoconfigure;

import io.seekflux.platform.agentruntime.application.spi.capability.observation.*;
import io.seekflux.platform.agentruntime.infrastructure.observation.*;
import io.seekflux.platform.agentruntime.domain.service.observation.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AgentObservationConfiguration {
    @Bean
    @ConditionalOnMissingBean
    ObservationCapturePolicy seekFluxObservationCapturePolicy(AgentRuntimeProperties properties) {
        var config = properties.getObservation();
        return new ObservationCapturePolicy(config.isCaptureContent(), config.getMaxCharacters(),
                config.getMaxNodes(), config.getMaxDepth(), config.getRedactedFields());
    }

    @Bean
    @ConditionalOnMissingBean
    AgentExecutionObserver seekFluxExecutionObserver(AgentRuntimeProperties properties,
            ObjectProvider<AgentObservationExporter> exporters) {
        var config = properties.getObservation();
        if (!config.isEnabled()) return AgentExecutionObserver.NOOP;
        var destinations = exporters.orderedStream().toList();
        if (destinations.isEmpty()) return AgentExecutionObserver.NOOP;
        return new AsyncObservationExporter(events -> {
            Exception failure = null;
            for (var destination : destinations) {
                try { destination.export(events); }
                catch (InterruptedException interrupted) { throw interrupted; }
                catch (Exception error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }, config.getQueueCapacity(), config.getBatchSize(), config.getMaxAttempts(),
                config.getShutdownTimeout());
    }

    @Bean
    @ConditionalOnMissingBean
    AgentObservationProcessor seekFluxObservationProcessor() {
        return AgentObservationProcessor.IDENTITY;
    }

    @Bean
    @ConditionalOnMissingBean
    AgentObservations seekFluxObservations(AgentRuntimeProperties properties,
            AgentExecutionObserver observer, ObservationCapturePolicy policy,
            AgentObservationProcessor processor) {
        return properties.getObservation().isEnabled() && observer != AgentExecutionObserver.NOOP
                ? new AgentObservations(observer, policy, processor) : AgentObservations.NOOP;
    }

}
