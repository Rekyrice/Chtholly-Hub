package com.chtholly.agent;

import com.chtholly.agent.response.AgentFinalAnswerService;
import com.chtholly.agent.runtime.AgentTurnPreparationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ChthollyAgentOrchestrationTest {

    @Test
    void springEntryPointDependsOnlyOnTurnPhaseCollaborators() {
        Constructor<?> springConstructor = Arrays.stream(ChthollyAgent.class.getConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing explicit Spring constructor"));

        long instanceFields = Arrays.stream(ChthollyAgent.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .count();

        assertThat(springConstructor.getParameterCount()).isLessThanOrEqualTo(2);
        assertThat(instanceFields).isLessThanOrEqualTo(2);
    }

    @Test
    void secondLevelApplicationServicesKeepNarrowDependencyBoundaries() {
        assertDependencyBoundary(AgentTurnPreparationService.class, 5);
        assertDependencyBoundary(AgentFinalAnswerService.class, 5);
    }

    private void assertDependencyBoundary(Class<?> type, int maximumDependencies) {
        long instanceFields = Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .count();
        int widestConstructor = Arrays.stream(type.getDeclaredConstructors())
                .mapToInt(Constructor::getParameterCount)
                .max()
                .orElse(0);

        assertThat(instanceFields)
                .as("%s instance dependencies", type.getSimpleName())
                .isLessThanOrEqualTo(maximumDependencies);
        assertThat(widestConstructor)
                .as("%s constructor dependencies", type.getSimpleName())
                .isLessThanOrEqualTo(maximumDependencies);
    }
}
