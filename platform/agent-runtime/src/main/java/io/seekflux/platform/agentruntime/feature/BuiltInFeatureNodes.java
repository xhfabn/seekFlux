package io.seekflux.platform.agentruntime.feature;

import io.seekflux.platform.agentruntime.session.AgentSession;
import io.seekflux.platform.agentruntime.session.AgentSessionStatus;
import io.seekflux.platform.agentruntime.session.AgentSessionStore;
import java.time.Clock;

public final class BuiltInFeatureNodes {

    private BuiltInFeatureNodes() {
    }

    public static final class SessionLoad implements FeatureNode {
        private final AgentSessionStore sessions;

        public SessionLoad(AgentSessionStore sessions) {
            this.sessions = sessions;
        }

        @Override
        public String name() {
            return "session-load";
        }

        @Override
        public int order() {
            return 100;
        }

        @Override
        public void process(FeatureContext context) {
            sessions.restoreFresh(context.request().runRequest().sessionId()).ifPresent(context::session);
        }
    }

    public static final class AgentResolve implements FeatureNode {
        private final AgentSessionStore sessions;
        private final Clock clock;

        public AgentResolve(AgentSessionStore sessions, Clock clock) {
            this.sessions = sessions;
            this.clock = clock;
        }

        @Override
        public String name() {
            return "agent-resolve";
        }

        @Override
        public int order() {
            return 200;
        }

        @Override
        public void process(FeatureContext context) {
            AgentSession session = context.session();
            if (session == null) {
                session = sessions.createIfAbsent(
                        context.request().runRequest().sessionId(),
                        context.request().definition(),
                        clock.instant());
                context.session(session);
            }
            if (!session.agentId().equals(context.request().definition().id())) {
                throw new IllegalStateException("session agent definition does not match the requested agent");
            }
        }
    }

    public static final class ParamInit implements FeatureNode {
        @Override
        public String name() {
            return "param-init";
        }

        @Override
        public int order() {
            return 300;
        }

        @Override
        public void process(FeatureContext context) {
            context.runtimeContext(new RuntimeContext(
                    context.request().definition(),
                    context.request().runRequest(),
                    context.request().llmClient(),
                    context.persistentAttributes()));
        }
    }

    public static final class ResumeEval implements FeatureNode {
        @Override
        public String name() {
            return "resume-eval";
        }

        @Override
        public int order() {
            return 400;
        }

        @Override
        public void process(FeatureContext context) {
            context.resumeAction(context.session().status() == AgentSessionStatus.EXECUTING
                    ? "NEW_EXECUTION_CONFLICT"
                    : "NEW_EXECUTION");
        }
    }
}
