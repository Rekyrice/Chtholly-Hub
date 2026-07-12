package com.chtholly.agent.config;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/** Scans the production extension marker boundary for context isolation tests. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
        basePackages = "com.chtholly.agent",
        useDefaultFilters = false,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.ANNOTATION,
                classes = AgentExtensionComponent.class),
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = ".*Test(\\$.*)?"))
public class AgentExtensionScanTestConfiguration {

    /**
     * Discovers every class inside the production extension marker boundary.
     *
     * @return marker-bearing extension component types
     */
    public static Set<Class<?>> extensionComponentTypes() {
        return scanTypes("com.chtholly.agent", AgentExtensionComponent.class);
    }

    /**
     * Discovers Spring component candidates beneath a package using production metadata.
     *
     * @param basePackage package boundary to scan
     * @return discovered component types
     */
    public static Set<Class<?>> springComponentTypes(String basePackage) {
        return scanTypes(basePackage, Component.class);
    }

    private static Set<Class<?>> scanTypes(
            String basePackage, Class<? extends java.lang.annotation.Annotation> annotationType) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "extension-scan",
                Map.of("llm.enabled", "true")));
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false, environment);
        scanner.addIncludeFilter(new AnnotationTypeFilter(annotationType, true));
        scanner.addExcludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*Test(\\$.*)?")));
        return scanner.findCandidateComponents(basePackage).stream()
                .map(definition -> loadClass(definition.getBeanClassName()))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Class<?> loadClass(String className) {
        try {
            return ClassUtils.forName(className, AgentExtensionScanTestConfiguration.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Extension component class is not loadable: " + className, e);
        }
    }
}
