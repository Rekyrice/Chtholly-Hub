package com.chtholly.architecture;

import com.chtholly.agent.proactive.ProactiveTriggerEngine;
import com.chtholly.agent.ws.AgentWebSocketHandler;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protects the backend's application boundaries and prevents structural debt from growing.
 */
class BackendArchitectureFitnessTest {

    private static final int MAX_COMPONENT_CONSTRUCTOR_DEPENDENCIES = 9;
    private static final int MAX_SOURCE_FILE_LINES = 500;
    private static final Set<String> FIELD_INJECTION_ANNOTATIONS = Set.of(
            "org.springframework.beans.factory.annotation.Autowired",
            "jakarta.annotation.Resource",
            "jakarta.inject.Inject",
            "javax.annotation.Resource",
            "javax.inject.Inject");
    private static final Set<String> LEGACY_FIELD_INJECTION_POINTS = Set.of(
            "com.chtholly.post.service.impl.PostServiceImpl#idGen");
    private static final Set<String> BUSINESS_PACKAGE_ROOTS = Set.of(
            "admin", "agent", "auth", "bangumi", "cache", "comment", "content", "counter",
            "llm", "notification", "post", "profile", "recommendation", "relation", "search",
            "seed", "storage", "tag", "user");
    private static final Set<String> LEGACY_COMMON_BUSINESS_DEPENDENCIES = Set.of(
            "com.chtholly.common.kafka.deadletter.DeadLetterController"
                    + " -> com.chtholly.admin.role.RequireRole",
            "com.chtholly.common.kafka.deadletter.DeadLetterController"
                    + " -> com.chtholly.admin.role.Role",
            "com.chtholly.common.kafka.deadletter.DeadLetterMessageService"
                    + " -> com.chtholly.post.id.SnowflakeIdGenerator",
            "com.chtholly.common.ratelimit.RateLimitContextResolver"
                    + " -> com.chtholly.auth.token.JwtService");
    private static final Map<String, Integer> LEGACY_COMPONENT_DEPENDENCY_BUDGETS = Map.ofEntries(
            Map.entry("com.chtholly.agent.ChthollyAgent", 16),
            Map.entry("com.chtholly.agent.draftedit.DraftEditService", 11),
            Map.entry("com.chtholly.agent.proactive.ContentProactiveService", 10),
            Map.entry("com.chtholly.agent.ws.AgentWebSocketHandler", 13),
            Map.entry("com.chtholly.auth.service.AuthService", 10),
            Map.entry("com.chtholly.post.service.impl.PersonalPostFeedService", 10),
            Map.entry("com.chtholly.post.service.impl.PostFeedServiceImpl", 11),
            Map.entry("com.chtholly.post.service.impl.PostServiceImpl", 14),
            Map.entry("com.chtholly.relation.outbox.CanalKafkaBridge", 13),
            Map.entry("com.chtholly.seed.contentpack.ContentPackImportService", 13));
    private static final Map<String, Integer> LEGACY_SOURCE_FILE_LINE_BUDGETS = Map.ofEntries(
            Map.entry("com.chtholly.agent.ChthollyAgent", 1593),
            Map.entry("com.chtholly.agent.content.TopicClusteringService", 712),
            Map.entry("com.chtholly.agent.draftedit.DraftEditService", 569),
            Map.entry("com.chtholly.agent.learning.InsightService", 532),
            Map.entry("com.chtholly.agent.observability.AgentExecutionTrace", 1594),
            Map.entry("com.chtholly.agent.observability.AgentTraceSanitizer", 510),
            Map.entry("com.chtholly.agent.runtime.AgentLoopExecutor", 1029),
            Map.entry("com.chtholly.agent.tools.WebFetchTool", 677),
            Map.entry("com.chtholly.agent.trace.dto.TraceDetailProjector", 1156),
            Map.entry("com.chtholly.agent.ws.AgentWebSocketHandler", 680),
            Map.entry("com.chtholly.auth.service.AuthService", 567),
            Map.entry("com.chtholly.bangumi.service.impl.BangumiServiceImpl", 572),
            Map.entry("com.chtholly.counter.service.impl.CounterServiceImpl", 559),
            Map.entry("com.chtholly.post.service.impl.PostFeedServiceImpl", 697),
            Map.entry("com.chtholly.post.service.impl.PostServiceImpl", 547),
            Map.entry("com.chtholly.relation.service.impl.RelationServiceImpl", 529),
            Map.entry("com.chtholly.search.service.impl.SearchServiceImpl", 534),
            Map.entry("com.chtholly.seed.contentpack.ContentPackDatabaseWriter", 798),
            Map.entry("com.chtholly.seed.contentpack.ContentPackMediaPublisher", 581),
            Map.entry("com.chtholly.seed.contentpack.ContentPackValidator", 697));
    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.chtholly");

    @Test
    void controllersDoNotDependDirectlyOnPersistenceOrInfrastructureClients() {
        List<String> violations = PRODUCTION_CLASSES.stream()
                .filter(this::isController)
                .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> isForbiddenControllerDependency(dependency.getTargetClass()))
                .map(this::describeDependency)
                .distinct()
                .sorted()
                .toList();

        assertThat(violations)
                .as("controllers must delegate persistence and infrastructure access to application services")
                .isEmpty();
    }

    @Test
    void mappersDoNotDependBackOnApiOrServiceLayers() {
        noClasses()
                .that().resideInAPackage("..mapper..")
                .should().dependOnClassesThat().resideInAnyPackage("..api..", "..service..")
                .because("persistence adapters must not reverse the dependency direction")
                .check(PRODUCTION_CLASSES);
    }

    @Test
    void fieldInjectionDoesNotExceedExplicitLegacyBaseline() {
        Set<String> injectedFields = PRODUCTION_CLASSES.stream()
                .flatMap(type -> Stream.of(type.reflect().getDeclaredFields()))
                .filter(this::isInjectionPoint)
                .map(field -> field.getDeclaringClass().getName() + "#" + field.getName())
                .collect(Collectors.toSet());

        assertThat(injectedFields)
                .as("field injection is frozen at an explicit legacy baseline; remove entries as debt is retired")
                .containsExactlyInAnyOrderElementsOf(LEGACY_FIELD_INJECTION_POINTS);
    }

    @Test
    void commonPackageDoesNotGainDependenciesOnBusinessPackages() {
        Set<String> dependencies = PRODUCTION_CLASSES.stream()
                .filter(type -> type.getPackageName().startsWith("com.chtholly.common"))
                .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> isBusinessPackage(dependency.getTargetClass().getPackageName()))
                .map(this::describeDependency)
                .collect(Collectors.toSet());

        assertThat(dependencies)
                .as("common-to-business dependencies are frozen at an explicit legacy baseline")
                .containsExactlyInAnyOrderElementsOf(LEGACY_COMMON_BUSINESS_DEPENDENCIES);
    }

    @Test
    void constructorBudgetMeasuresTheSpringInjectionConstructorRatherThanTestSeams() {
        assertThat(springInjectionConstructorParameters(ProactiveTriggerEngine.class))
                .as("ProactiveTriggerEngine production constructor")
                .isEqualTo(3);
        assertThat(springInjectionConstructorParameters(AgentWebSocketHandler.class))
                .as("AgentWebSocketHandler production constructor")
                .isEqualTo(13);
    }

    @Test
    void springComponentConstructorDependenciesStayWithinBudget() {
        List<ComponentDependencyCount> dependencyCounts = PRODUCTION_CLASSES.stream()
                .filter(this::isConcreteSpringComponent)
                .map(JavaClass::reflect)
                .map(type -> new ComponentDependencyCount(
                        type.getName(),
                        springInjectionConstructorParameters(type)))
                .sorted(Comparator.comparing(ComponentDependencyCount::className))
                .toList();
        List<String> budgetViolations = dependencyCounts.stream()
                .filter(component -> component.dependencies() > componentDependencyBudget(component.className()))
                .map(component -> describeBudgetViolation(
                        component.className(),
                        component.dependencies(),
                        componentDependencyBudget(component.className())))
                .toList();
        List<String> staleLegacyBudgets = staleBudgets(
                LEGACY_COMPONENT_DEPENDENCY_BUDGETS,
                dependencyCounts.stream().collect(Collectors.toMap(
                        ComponentDependencyCount::className,
                        ComponentDependencyCount::dependencies)),
                MAX_COMPONENT_CONSTRUCTOR_DEPENDENCIES);

        assertThat(budgetViolations)
                .as("constructor dependency counts may not exceed the global or per-class legacy budget")
                .isEmpty();
        assertThat(staleLegacyBudgets)
                .as("lower each legacy constructor budget in the same change that reduces its dependency count")
                .isEmpty();
    }

    @Test
    void productionSourceFilesStayWithinLengthBudget() throws IOException {
        Path sourceRoot = productionSourceRoot();
        Map<String, Integer> sourceLineCounts = new HashMap<>();
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path sourceFile : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sourceLineCounts.put(sourceName(sourceRoot, sourceFile), lineCount(sourceFile));
            }
        }
        List<String> budgetViolations = sourceLineCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > sourceLineBudget(entry.getKey()))
                .map(entry -> describeBudgetViolation(
                        entry.getKey(),
                        entry.getValue(),
                        sourceLineBudget(entry.getKey())))
                .sorted()
                .toList();
        List<String> staleLegacyBudgets = staleBudgets(
                LEGACY_SOURCE_FILE_LINE_BUDGETS,
                sourceLineCounts,
                MAX_SOURCE_FILE_LINES);

        assertThat(budgetViolations)
                .as("source files may not exceed the global or per-file legacy line budget")
                .isEmpty();
        assertThat(staleLegacyBudgets)
                .as("lower each legacy line budget in the same change that shortens its source file")
                .isEmpty();
    }

    private boolean isController(JavaClass type) {
        Class<?> reflected = type.reflect();
        return AnnotatedElementUtils.hasAnnotation(reflected, RestController.class)
                || AnnotatedElementUtils.hasAnnotation(reflected, Controller.class);
    }

    private boolean isSpringComponent(JavaClass type) {
        return AnnotatedElementUtils.hasAnnotation(type.reflect(), Component.class);
    }

    private boolean isConcreteSpringComponent(JavaClass type) {
        Class<?> reflected = type.reflect();
        return isSpringComponent(type)
                && !reflected.isInterface()
                && !Modifier.isAbstract(reflected.getModifiers());
    }

    private boolean isForbiddenControllerDependency(JavaClass target) {
        String packageName = target.getPackageName();
        return packageName.contains(".mapper")
                || packageName.startsWith("org.springframework.data.redis")
                || packageName.startsWith("org.redisson")
                || packageName.startsWith("org.springframework.kafka")
                || packageName.startsWith("org.apache.kafka")
                || packageName.startsWith("co.elastic.clients")
                || packageName.startsWith("org.elasticsearch")
                || packageName.startsWith("org.springframework.data.elasticsearch");
    }

    private boolean isBusinessPackage(String packageName) {
        return BUSINESS_PACKAGE_ROOTS.stream()
                .map(root -> "com.chtholly." + root)
                .anyMatch(prefix -> packageName.equals(prefix) || packageName.startsWith(prefix + "."));
    }

    private String describeDependency(Dependency dependency) {
        return dependency.getOriginClass().getName() + " -> " + dependency.getTargetClass().getName();
    }

    private boolean isInjectionPoint(Field field) {
        for (Annotation annotation : field.getDeclaredAnnotations()) {
            if (FIELD_INJECTION_ANNOTATIONS.contains(annotation.annotationType().getName())) {
                return true;
            }
        }
        return false;
    }

    private int springInjectionConstructorParameters(Class<?> type) {
        List<Constructor<?>> constructors = Stream.of(type.getDeclaredConstructors())
                .filter(constructor -> !constructor.isSynthetic())
                .toList();
        List<Constructor<?>> explicitlyAutowired = constructors.stream()
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .toList();
        if (!explicitlyAutowired.isEmpty()) {
            return explicitlyAutowired.stream()
                    .mapToInt(Constructor::getParameterCount)
                    .max()
                    .orElse(0);
        }
        if (constructors.size() == 1) {
            return constructors.getFirst().getParameterCount();
        }
        return constructors.stream()
                .filter(constructor -> constructor.getParameterCount() == 0)
                .mapToInt(Constructor::getParameterCount)
                .findFirst()
                .orElse(0);
    }

    private int componentDependencyBudget(String className) {
        return LEGACY_COMPONENT_DEPENDENCY_BUDGETS.getOrDefault(
                className,
                MAX_COMPONENT_CONSTRUCTOR_DEPENDENCIES);
    }

    private int sourceLineBudget(String sourceName) {
        return LEGACY_SOURCE_FILE_LINE_BUDGETS.getOrDefault(sourceName, MAX_SOURCE_FILE_LINES);
    }

    private List<String> staleBudgets(
            Map<String, Integer> legacyBudgets,
            Map<String, Integer> actualValues,
            int defaultBudget) {
        return legacyBudgets.entrySet().stream()
                .filter(entry -> actualValues.getOrDefault(entry.getKey(), 0) < entry.getValue())
                .map(entry -> {
                    int actual = actualValues.getOrDefault(entry.getKey(), 0);
                    String nextBudget = actual <= defaultBudget ? "remove" : Integer.toString(actual);
                    return entry.getKey() + ": actual=" + actual + ", baseline=" + entry.getValue()
                            + ", next=" + nextBudget;
                })
                .sorted()
                .toList();
    }

    private String describeBudgetViolation(String subject, int actual, int budget) {
        return subject + ": actual=" + actual + ", budget=" + budget;
    }

    private Path productionSourceRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path moduleSourceRoot = workingDirectory.resolve("src/main/java");
        if (Files.isDirectory(moduleSourceRoot)) {
            return moduleSourceRoot;
        }
        Path repositorySourceRoot = workingDirectory.resolve("apps/server/src/main/java");
        assertThat(repositorySourceRoot)
                .as("backend production source root")
                .isDirectory();
        return repositorySourceRoot;
    }

    private int lineCount(Path sourceFile) throws IOException {
        try (Stream<String> lines = Files.lines(sourceFile)) {
            return Math.toIntExact(lines.count());
        }
    }

    private String sourceName(Path sourceRoot, Path sourceFile) {
        String relative = sourceRoot.relativize(sourceFile).toString();
        return relative.substring(0, relative.length() - ".java".length())
                .replace('/', '.')
                .replace('\\', '.');
    }

    private record ComponentDependencyCount(String className, int dependencies) {
    }
}
