package io.seekflux.agent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * 编排层边界：契约不依赖实现，业务不依赖进程。
 * Runtime 内核以发布制品形式引入，不在本模块内，故不设内核/编排隔离规则。
 */
class AgentArchitectureTest {

    private static final JavaClasses AGENT = new ClassFileImporter().importPackages("io.seekflux.agent");

    @Test
    void agent_不得依赖_apps() {
        assertRule(noClasses().should().dependOnClassesThat().resideInAPackage("io.seekflux.apps.."));
    }

    @Test
    void api_不得依赖_adapter() {
        assertRule(noClasses().that().resideInAPackage("io.seekflux.agent.api..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.seekflux.agent.orchestration.adapter..", "io.seekflux.agent.adapter.."));
    }

    @Test
    void 编排不得引用_app_配置() {
        assertRule(noClasses().that().resideOutsideOfPackage("io.seekflux.agent.config..")
                .should().dependOnClassesThat().resideInAPackage("io.seekflux.agent.config.."));
    }

    private static void assertRule(ArchRule rule) {
        assertThatCode(() -> rule.check(AGENT)).doesNotThrowAnyException();
    }
}
