package io.seekflux.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/** 模块内依赖边界：违反即构建失败，依赖方向不再只靠文档约束。 */
class CoreArchitectureTest {

    private static final JavaClasses CORE = new ClassFileImporter().importPackages("io.seekflux.core");

    @Test
    void core_不得依赖_agent() {
        assertRule(noClasses().should().dependOnClassesThat().resideInAnyPackage("io.seekflux.agent.."));
    }

    @Test
    void search_只能引用_feature_契约() {
        assertRule(noClasses().that().resideInAPackage("io.seekflux.core.search..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.seekflux.core.feature.domain..", "io.seekflux.core.feature.adapter.."));
    }

    @Test
    void recommendation_只能引用_feature_契约() {
        assertRule(noClasses().that().resideInAPackage("io.seekflux.core.recommendation..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.seekflux.core.feature.domain..", "io.seekflux.core.feature.adapter.."));
    }

    @Test
    void domain_不依赖框架与适配器() {
        assertRule(noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "org.elasticsearch..", "org.apache.kafka..",
                        "com.fasterxml..", "redis.clients..", "..adapter.."));
    }

    private static void assertRule(ArchRule rule) {
        assertThatCode(() -> rule.check(CORE)).doesNotThrowAnyException();
    }
}
