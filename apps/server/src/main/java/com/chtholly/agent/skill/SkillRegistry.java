package com.chtholly.agent.skill;

import com.chtholly.agent.AgentTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Loads and validates the closed set of classpath Skill definitions at startup. */
@Component
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class SkillRegistry {

    private static final String RESOURCE_PATTERN = "classpath*:agent/skills/*/*.yml";
    private static final Map<String, RiskContract> CLOSED_CONTRACTS = Map.of(
            "page-explain@v1", new RiskContract("READ_ONLY", "NONE"),
            "evidence-outline@v1", new RiskContract("READ_ONLY", "NONE"),
            "draft-fact-check@v1", new RiskContract("READ_ONLY", "NONE"),
            "draft-edit@v1", new RiskContract("CONTROLLED_WRITE", "EXPLICIT_CONFIRMATION"));

    private final Map<String, SkillDefinition> definitions;
    private final List<SkillDefinition> enabled;

    @Autowired
    public SkillRegistry(ResourcePatternResolver resolver,
                         List<AgentTool> tools,
                         SkillOutputValidator validator,
                         Environment environment) throws IOException {
        this(
                List.of(resolver.getResources(RESOURCE_PATTERN)),
                tools.stream().map(AgentTool::name).collect(java.util.stream.Collectors.toSet()),
                validator,
                id -> environment.getProperty("agent.skills." + id + ".enabled", Boolean.class, true));
    }

    SkillRegistry(List<? extends Resource> resources,
                  Set<String> availableTools,
                  SkillOutputValidator validator,
                  Predicate<String> enabledOverride) {
        Map<String, SkillDefinition> loaded = new LinkedHashMap<>();
        for (Resource resource : resources) {
            SkillDefinition definition = read(resource);
            validateReferences(definition, availableTools, validator.supportedValidatorIds());
            if (loaded.putIfAbsent(definition.key(), definition) != null) {
                throw new IllegalStateException("Duplicate Skill " + definition.key());
            }
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("No Skill definitions found at " + RESOURCE_PATTERN);
        }
        definitions = Map.copyOf(loaded);
        enabled = loaded.values().stream()
                .filter(SkillDefinition::enabled)
                .filter(definition -> enabledOverride.test(definition.id()))
                .sorted(Comparator.comparing(SkillDefinition::id).thenComparing(SkillDefinition::version))
                .toList();
    }

    public List<SkillDefinition> enabled() {
        return enabled;
    }

    public SkillDefinition require(String id, String version) {
        SkillDefinition definition = definitions.get(id + '@' + version);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown Skill " + id + '@' + version);
        }
        return definition;
    }

    private SkillDefinition read(Resource resource) {
        final Map<String, Object> yaml;
        try (InputStream input = resource.getInputStream()) {
            Object value = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Skill resource is not a mapping: " + resource);
            }
            yaml = new LinkedHashMap<>();
            map.forEach((key, item) -> yaml.put(String.valueOf(key), item));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot load Skill resource " + resource, e);
        }
        try {
            return new SkillDefinition(
                    text(yaml, "id"), text(yaml, "version"), bool(yaml, "enabled", true),
                    text(yaml, "description"), strings(yaml.get("supportedIntents")),
                    strings(yaml.get("requiredContext")), strings(yaml.get("allowedTools")),
                    text(yaml, "instructionTemplate"), objectMap(yaml.get("inputSchema")),
                    objectMap(yaml.get("outputSchema")), strings(yaml.get("validators")),
                    text(yaml, "riskLevel"), text(yaml, "approvalPolicy"),
                    number(yaml, "timeoutBudgetMs"), Math.toIntExact(number(yaml, "maxSteps")),
                    text(yaml, "evaluationDatasetVersion"));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Invalid Skill resource " + resource + ": " + e.getMessage(), e);
        }
    }

    private void validateReferences(
            SkillDefinition definition,
            Set<String> availableTools,
            Set<String> availableValidators) {
        RiskContract contract = CLOSED_CONTRACTS.get(definition.key());
        if (contract == null) {
            throw new IllegalStateException("Unsupported Skill " + definition.key());
        }
        Set<String> uniqueTools = new LinkedHashSet<>();
        for (String tool : definition.allowedTools()) {
            if (!uniqueTools.add(tool)) {
                throw new IllegalStateException("Duplicate Skill tool " + tool + " in " + definition.key());
            }
            if (!availableTools.contains(tool)) {
                throw new IllegalStateException("Unknown Skill tool " + tool + " in " + definition.key());
            }
        }
        for (String validator : definition.validators()) {
            if (!availableValidators.contains(validator)) {
                throw new IllegalStateException(
                        "Unknown Skill validator " + validator + " in " + definition.key());
            }
        }
        if (!contract.riskLevel().equals(definition.riskLevel())
                || !contract.approvalPolicy().equals(definition.approvalPolicy())) {
            throw new IllegalStateException("Skill risk contract mismatch: " + definition.key());
        }
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static long number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        iterable.forEach(item -> values.add(String.valueOf(item)));
        return List.copyOf(values);
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        map.forEach((key, item) -> values.put(String.valueOf(key), item));
        return Map.copyOf(values);
    }

    private record RiskContract(String riskLevel, String approvalPolicy) {
    }
}
