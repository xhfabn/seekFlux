package io.seekflux.apps.agentserver.interfaces.rest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.domain.service.capability.CapabilityResolver;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

class AgentCapabilityValidationTest {

    @Test
    void startsWithMethodValidationAndRejectsInvalidSessionIds() {
        try (var context = new GenericApplicationContext()) {
            context.registerBean(MethodValidationPostProcessor.class);
            context.registerBean(AgentCapabilityController.class, () -> new AgentCapabilityController(
                    mock(AgentSessionStore.class), Map.of(), mock(CapabilityResolver.class), Clock.systemUTC()));
            context.refresh();

            var controller = context.getBean(AgentCapabilityController.class);
            assertTrue(AopUtils.isAopProxy(controller));
            assertThrows(ConstraintViolationException.class, () -> controller.get("x".repeat(129)));
        }
    }
}
