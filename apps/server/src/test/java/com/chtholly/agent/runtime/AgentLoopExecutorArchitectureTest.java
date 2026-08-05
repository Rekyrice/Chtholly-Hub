package com.chtholly.agent.runtime;

import com.chtholly.agent.AgentJsonExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopExecutorArchitectureTest {

    @Test
    void loopDependsOnPhaseCollaboratorsInsteadOfModelToolAndJsonInfrastructure() {
        List<Constructor<?>> declaredConstructors = Arrays.stream(
                        AgentLoopExecutor.class.getDeclaredConstructors())
                .filter(constructor -> !constructor.isSynthetic())
                .toList();
        Constructor<?> springConstructor = declaredConstructors.stream()
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing explicit Spring constructor"));

        assertThat(declaredConstructors)
                .as("loop wiring must not hide alternate composition constructors")
                .hasSize(1);
        assertThat(springConstructor.getParameterCount()).isLessThanOrEqualTo(6);
        List<String> fieldTypes = Arrays.stream(AgentLoopExecutor.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(field -> field.getType().getName())
                .toList();
        assertThat(fieldTypes)
                .hasSizeLessThanOrEqualTo(6)
                .doesNotContain(
                        AgentLlmInvoker.class.getName(),
                        AgentToolExecutor.class.getName(),
                        AgentJsonExtractor.class.getName())
                .contains(
                        AgentDecisionGateway.class.getName(),
                        AgentActionParser.class.getName());
    }

    @Test
    void loopPhaseClassesRemainWithinTheArchitectureSizeBudget() throws Exception {
        for (Class<?> type : List.of(
                AgentLoopExecutor.class,
                AgentDecisionGateway.class,
                AgentActionParser.class,
                AgentLoopCompletionPolicy.class,
                AgentEvidenceTracker.class,
                AgentToolCallService.class)) {
            Path classesDirectory = Path.of(
                    type.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path moduleRoot = classesDirectory.getParent().getParent();
            Path source = moduleRoot.resolve("src/main/java/")
                    .resolve(type.getName().replace('.', '/') + ".java");
            assertThat(Files.readAllLines(source))
                    .as("%s source lines", type.getSimpleName())
                    .hasSizeLessThanOrEqualTo(
                            type == AgentLoopExecutor.class ? 450 : 500);
        }
    }
}
