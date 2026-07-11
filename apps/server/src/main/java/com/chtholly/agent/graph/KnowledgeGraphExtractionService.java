package com.chtholly.agent.graph;

import com.chtholly.agent.config.AgentExtensionComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts graph entities and candidate relations from free text.
 */
@Slf4j
@Service
@AgentExtensionComponent
@ConditionalOnProperty(prefix = "agent.extensions.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeGraphExtractionService {

    private static final Pattern WORK_PATTERN = Pattern.compile("《([^》]{1,80})》");
    private static final Pattern USER_PATTERN = Pattern.compile("@([A-Za-z0-9_\\-\\u4e00-\\u9fa5]{2,32})");
    private static final Pattern TAG_PATTERN = Pattern.compile("#([A-Za-z0-9_\\-\\u4e00-\\u9fa5]{1,32})");
    private static final List<String> TECH_CONCEPTS = List.of(
            "Java", "Python", "Redis", "Elasticsearch", "Spring Boot", "Next.js",
            "RAG", "Agent", "Embedding", "MySQL", "Kafka", "Docker", "React");

    private final ObjectMapper objectMapper;
    private final ObjectProvider<ChatClient> chatClientProvider;

    public KnowledgeGraphExtractionService(ObjectMapper objectMapper, ObjectProvider<ChatClient> chatClientProvider) {
        this.objectMapper = objectMapper;
        this.chatClientProvider = chatClientProvider;
    }

    /**
     * Runs rule extraction first and merges optional LLM extraction results.
     */
    public KnowledgeExtractionResult extract(String text) {
        if (!StringUtils.hasText(text)) {
            return KnowledgeExtractionResult.empty();
        }
        Map<String, ExtractedKnowledgeEntity> entities = new LinkedHashMap<>();
        addRuleEntities(text, entities);
        mergeLlmExtraction(text, entities);
        List<ExtractedKnowledgeEntity> entityList = new ArrayList<>(entities.values());
        return new KnowledgeExtractionResult(entityList, buildCoOccurrenceRelations(entityList));
    }

    private void addRuleEntities(String text, Map<String, ExtractedKnowledgeEntity> entities) {
        addPatternMatches(text, WORK_PATTERN, KnowledgeEntityType.WORK, entities);
        addPatternMatches(text, USER_PATTERN, KnowledgeEntityType.PERSON, entities);
        addPatternMatches(text, TAG_PATTERN, KnowledgeEntityType.TAG, entities);
        String lower = text.toLowerCase();
        for (String concept : TECH_CONCEPTS) {
            if (lower.contains(concept.toLowerCase())) {
                addEntity(entities, new ExtractedKnowledgeEntity(concept, KnowledgeEntityType.CONCEPT, "", List.of()));
            }
        }
    }

    private void mergeLlmExtraction(String text, Map<String, ExtractedKnowledgeEntity> entities) {
        ChatClient chatClient = chatClientProvider == null ? null : chatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return;
        }
        String prompt = """
                Extract entities from the following text for a knowledge graph.
                Return JSON only with this shape:
                {"entities":[{"name":"...","type":"PERSON|WORK|CONCEPT|TAG","description":"...","aliases":[]}]}.

                Text:
                %s
                """.formatted(text.length() > 3000 ? text.substring(0, 3000) : text);
        try {
            String json = chatClient.prompt().user(prompt).call().content();
            if (!StringUtils.hasText(json)) {
                return;
            }
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {
            });
            Object raw = data.get("entities");
            if (!(raw instanceof List<?> list)) {
                return;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    ExtractedKnowledgeEntity entity = toEntity(map);
                    if (entity != null) {
                        addEntity(entities, entity);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Knowledge graph LLM extraction failed: {}", e.getMessage());
        }
    }

    private ExtractedKnowledgeEntity toEntity(Map<?, ?> map) {
        Object name = map.get("name");
        if (name == null || !StringUtils.hasText(String.valueOf(name))) {
            return null;
        }
        KnowledgeEntityType type = parseType(String.valueOf(map.get("type")));
        Object aliasesRaw = map.get("aliases");
        List<String> aliases = aliasesRaw instanceof List<?> list
                ? list.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList()
                : List.of();
        return new ExtractedKnowledgeEntity(
                String.valueOf(name).trim(),
                type,
                map.get("description") == null ? "" : String.valueOf(map.get("description")),
                aliases);
    }

    private void addPatternMatches(String text,
                                   Pattern pattern,
                                   KnowledgeEntityType type,
                                   Map<String, ExtractedKnowledgeEntity> entities) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            addEntity(entities, new ExtractedKnowledgeEntity(matcher.group(1).trim(), type, "", List.of()));
        }
    }

    private void addEntity(Map<String, ExtractedKnowledgeEntity> entities, ExtractedKnowledgeEntity entity) {
        if (entity == null || !StringUtils.hasText(entity.name())) {
            return;
        }
        entities.putIfAbsent(entity.type() + ":" + entity.name().trim().toLowerCase(), entity);
    }

    private List<ExtractedKnowledgeRelation> buildCoOccurrenceRelations(List<ExtractedKnowledgeEntity> entities) {
        if (entities.size() < 2) {
            return List.of();
        }
        List<ExtractedKnowledgeRelation> relations = new ArrayList<>();
        for (int i = 0; i < entities.size(); i++) {
            for (int j = i + 1; j < entities.size(); j++) {
                relations.add(new ExtractedKnowledgeRelation(
                        entities.get(i).name(),
                        entities.get(j).name(),
                        KnowledgeRelationType.RELATED_TO,
                        0.65));
            }
        }
        return relations;
    }

    private KnowledgeEntityType parseType(String value) {
        if (!StringUtils.hasText(value)) {
            return KnowledgeEntityType.CONCEPT;
        }
        try {
            return KnowledgeEntityType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return KnowledgeEntityType.CONCEPT;
        }
    }
}
