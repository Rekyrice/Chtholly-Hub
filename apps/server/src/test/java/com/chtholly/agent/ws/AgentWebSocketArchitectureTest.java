package com.chtholly.agent.ws;

import com.chtholly.agent.ChthollyAgent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the WebSocket application-service architecture. */
class AgentWebSocketArchitectureTest {

    private static final Map<Class<?>, BoundaryBudget> BOUNDARY_BUDGETS =
            Map.ofEntries(
                    Map.entry(
                            AgentWebSocketHandler.class,
                            new BoundaryBudget(90, 2, 1, 2)),
                    Map.entry(
                            AgentWebSocketConnectionLifecycle.class,
                            new BoundaryBudget(285, 7, 1, 7)),
                    Map.entry(
                            AgentWebSocketConnectionRegistry.class,
                            new BoundaryBudget(260, 1, 1, 0)),
                    Map.entry(
                            AgentWebSocketProtocolCodec.class,
                            new BoundaryBudget(265, 1, 1, 1)),
                    Map.entry(
                            AgentWebSocketTaskExecutor.class,
                            new BoundaryBudget(90, 2, 3, 2)),
                    Map.entry(
                            AgentWebSocketExtensionLifecycle.class,
                            new BoundaryBudget(260, 7, 1, 7)),
                    Map.entry(
                            AgentWebSocketDeliveryService.class,
                            new BoundaryBudget(190, 2, 1, 2)),
                    Map.entry(
                            AgentWebSocketTurnAdmissionService.class,
                            new BoundaryBudget(180, 5, 1, 5)),
                    Map.entry(
                            AgentWebSocketAcceptedTurnRunner.class,
                            new BoundaryBudget(225, 5, 1, 5)),
                    Map.entry(
                            AgentWebSocketTurnSubmissionService.class,
                            new BoundaryBudget(70, 2, 1, 2)),
                    Map.entry(
                            AgentWebSocketProtocolDispatcher.class,
                            new BoundaryBudget(220, 5, 1, 5)));

    @Test
    void springHandlerRemainsAThinFrameworkAdapter() throws Exception {
        Path source = Path.of(
                "src/main/java/com/chtholly/agent/ws/AgentWebSocketHandler.java");

        assertThat(Files.readAllLines(source)).hasSizeLessThanOrEqualTo(180);
        assertThat(instanceFields(AgentWebSocketHandler.class))
                .hasSizeLessThanOrEqualTo(5)
                .noneMatch(field -> Map.class.isAssignableFrom(field.getType()))
                .noneMatch(field -> Collection.class.isAssignableFrom(
                        field.getType()))
                .noneMatch(field -> AtomicReference.class.isAssignableFrom(
                        field.getType()));
        assertThat(java.util.Arrays.stream(
                        AgentWebSocketHandler.class.getDeclaredConstructors()))
                .allMatch(constructor ->
                        constructor.getParameterCount() <= 5);
    }

    @Test
    void everyWebSocketBoundaryHasAnExplicitSizeAndDependencyBudget()
            throws Exception {
        for (Map.Entry<Class<?>, BoundaryBudget> entry
                : BOUNDARY_BUDGETS.entrySet()) {
            Class<?> type = entry.getKey();
            BoundaryBudget budget = entry.getValue();
            Path source = Path.of(
                    "src/main/java/com/chtholly/agent/ws/"
                            + type.getSimpleName() + ".java");

            assertThat(Files.readAllLines(source))
                    .as("line budget for %s", type.getSimpleName())
                    .hasSizeLessThanOrEqualTo(budget.maxLines());
            assertThat(instanceFields(type))
                    .as("field budget for %s", type.getSimpleName())
                    .hasSizeLessThanOrEqualTo(budget.maxFields());
            assertThat(type.getDeclaredConstructors())
                    .as("constructor count for %s", type.getSimpleName())
                    .hasSize(budget.constructorCount())
                    .allMatch(constructor -> constructor.getParameterCount()
                            <= budget.maxConstructorParameters());
        }
    }

    @Test
    void frameworkAndProtocolBoundariesCannotReachTheAgentRuntimeDirectly() {
        Set<Class<?>> directRuntimeOwners = Set.of(
                AgentWebSocketAcceptedTurnRunner.class);

        assertThat(BOUNDARY_BUDGETS.keySet())
                .filteredOn(type -> !directRuntimeOwners.contains(type))
                .allSatisfy(type -> assertThat(instanceFields(type))
                        .noneMatch(field ->
                                field.getType() == ChthollyAgent.class));
        assertThat(instanceFields(AgentWebSocketHandler.class))
                .extracting(Field::getType)
                .containsExactlyInAnyOrder(
                        AgentWebSocketConnectionLifecycle.class,
                        AgentWebSocketProtocolDispatcher.class);
        assertThat(instanceFields(AgentWebSocketProtocolDispatcher.class))
                .extracting(Field::getType)
                .doesNotContain(
                        AgentTurnCoordinator.class,
                        ChthollyAgent.class);
    }

    @Test
    void activeTurnIsAPlainPerTurnStateObject() {
        assertThat(AgentWebSocketActiveTurn.class
                .isAnnotationPresent(Component.class)).isFalse();
        assertThat(AgentWebSocketActiveTurn.class
                .isAnnotationPresent(ConditionalOnProperty.class)).isFalse();
        assertThat(instanceFields(AgentWebSocketActiveTurn.class))
                .hasSizeLessThanOrEqualTo(6)
                .noneMatch(field -> Modifier.isStatic(field.getModifiers()));
    }

    @Test
    void singletonServicesDoNotStorePerTurnCollections() {
        assertThat(BOUNDARY_BUDGETS.keySet())
                .filteredOn(type ->
                        type != AgentWebSocketConnectionRegistry.class)
                .allSatisfy(type -> assertThat(instanceFields(type))
                        .noneMatch(AgentWebSocketArchitectureTest
                                ::isMutableTurnState));
    }

    @Test
    void allNewSpringBeansFollowTheAgentFeatureFlag() {
        assertThat(new Class<?>[]{
                AgentWebSocketConnectionRegistry.class,
                AgentWebSocketProtocolCodec.class,
                AgentWebSocketTaskExecutor.class,
                AgentWebSocketExtensionLifecycle.class,
                AgentWebSocketDeliveryService.class,
                AgentWebSocketTurnAdmissionService.class,
                AgentWebSocketAcceptedTurnRunner.class,
                AgentWebSocketTurnSubmissionService.class,
                AgentWebSocketProtocolDispatcher.class,
                AgentWebSocketConnectionLifecycle.class
        }).allSatisfy(type -> assertThat(type)
                .hasAnnotation(ConditionalOnProperty.class));
    }

    private static java.util.List<Field> instanceFields(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList();
    }

    private static boolean isMutableTurnState(Field field) {
        return Map.class.isAssignableFrom(field.getType())
                || Collection.class.isAssignableFrom(field.getType())
                || AtomicReference.class.isAssignableFrom(field.getType());
    }

    private record BoundaryBudget(
            int maxLines,
            int maxFields,
            int constructorCount,
            int maxConstructorParameters) {
    }
}
