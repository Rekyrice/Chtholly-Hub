package com.chtholly.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExtensionBoundaryArchitectureTest {

    private static final List<String> EXTENSION_PACKAGES = List.of(
            "com.chtholly.agent.content",
            "com.chtholly.agent.graph",
            "com.chtholly.agent.learning",
            "com.chtholly.agent.experience",
            "com.chtholly.agent.cognitive",
            "com.chtholly.agent.mood",
            "com.chtholly.agent.comment",
            "com.chtholly.agent.notification",
            "com.chtholly.agent.proactive");

    @Test
    void extensionPackagesUseMarkerBoundaryForEverySpringComponent() {
        for (String extensionPackage : EXTENSION_PACKAGES) {
            assertThat(AgentExtensionScanTestConfiguration.springComponentTypes(extensionPackage))
                    .allSatisfy(type -> assertThat(AnnotatedElementUtils.hasAnnotation(
                                    type, AgentExtensionComponent.class))
                            .as("extension component %s", type.getName())
                            .isTrue());
        }
    }

    @Test
    void crossPackageExtensionConditionsAlsoCarryMarkerBoundary() {
        assertThat(AgentExtensionScanTestConfiguration.springComponentTypes("com.chtholly.agent"))
                .filteredOn(this::hasSimpleExtensionCondition)
                .allSatisfy(type -> assertThat(AnnotatedElementUtils.hasAnnotation(
                                type, AgentExtensionComponent.class))
                        .as("cross-package extension component %s", type.getName())
                        .isTrue());
    }

    @Test
    void extensionBoundaryDoesNotUseStringExpressionsForCombinationConditions() {
        assertThat(AgentExtensionScanTestConfiguration.extensionComponentTypes())
                .filteredOn(this::usesExtensionStringExpression)
                .as("extension components with string-based extension conditions")
                .isEmpty();
    }

    private boolean hasSimpleExtensionCondition(Class<?> type) {
        ConditionalOnProperty condition = AnnotatedElementUtils.findMergedAnnotation(
                type, ConditionalOnProperty.class);
        return condition != null && condition.prefix().startsWith("agent.extensions.");
    }

    private boolean usesExtensionStringExpression(Class<?> type) {
        ConditionalOnExpression condition = AnnotatedElementUtils.findMergedAnnotation(
                type, ConditionalOnExpression.class);
        return condition != null && condition.value().contains("agent.extensions.");
    }
}
