package com.chtholly.agent.graph;

import com.chtholly.agent.content.ContentAnalysis;
import com.chtholly.agent.content.Entity;
import com.chtholly.post.id.SnowflakeIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds and traverses the Chtholly topic knowledge graph.
 */
@Slf4j
@Service
public class KnowledgeGraphService {

    private static final int PATH_MAX_DEPTH = 4;

    private final KnowledgeGraphRepository repository;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;
    private final Supplier<Instant> nowSupplier;

    @Autowired
    public KnowledgeGraphService(KnowledgeGraphRepository repository,
                                 SnowflakeIdGenerator idGenerator,
                                 ObjectMapper objectMapper) {
        this(repository, idGenerator, objectMapper, Instant::now);
    }

    KnowledgeGraphService(KnowledgeGraphRepository repository,
                          SnowflakeIdGenerator idGenerator,
                          ObjectMapper objectMapper,
                          Supplier<Instant> nowSupplier) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.objectMapper = objectMapper;
        this.nowSupplier = nowSupplier;
    }

    /**
     * Adds or updates an entity by name and type.
     */
    public KnowledgeEntity addEntity(String name, KnowledgeEntityType type, String description) {
        return addEntity(name, type, description, List.of());
    }

    /**
     * Adds or updates an entity by name and type, merging aliases.
     */
    public KnowledgeEntity addEntity(String name, KnowledgeEntityType type, String description, List<String> aliases) {
        String normalizedName = normalizeName(name);
        if (!StringUtils.hasText(normalizedName)) {
            throw new IllegalArgumentException("Entity name must not be blank");
        }
        KnowledgeEntityType safeType = type == null ? KnowledgeEntityType.CONCEPT : type;
        Instant now = nowSupplier.get();
        Optional<KnowledgeEntity> existing = repository.findEntityByNameAndType(normalizedName, safeType);
        if (existing.isPresent()) {
            KnowledgeEntity current = existing.get();
            return repository.saveEntity(new KnowledgeEntity(
                    current.id(),
                    current.name(),
                    current.type(),
                    StringUtils.hasText(description) ? description : current.description(),
                    mergeAliases(current.aliases(), aliases),
                    current.embedding(),
                    current.metadata(),
                    current.createdAt(),
                    now));
        }
        return repository.saveEntity(new KnowledgeEntity(
                idGenerator.nextId(),
                normalizedName,
                safeType,
                description == null ? "" : description,
                aliases == null ? List.of() : List.copyOf(aliases),
                null,
                "{}",
                now,
                now));
    }

    /**
     * Adds a directed weighted relation.
     */
    public KnowledgeRelation addRelation(long sourceId, long targetId, KnowledgeRelationType type, double weight) {
        return repository.saveRelation(new KnowledgeRelation(
                idGenerator.nextId(),
                sourceId,
                targetId,
                type == null ? KnowledgeRelationType.RELATED_TO : type,
                clampWeight(weight),
                "{}",
                nowSupplier.get()));
    }

    /**
     * Traverses related entities with BFS up to the requested depth.
     */
    public List<KnowledgeEntity> findRelatedEntities(long entityId, int maxDepth, double minWeight) {
        if (maxDepth <= 0) {
            return List.of();
        }
        Map<Long, Double> scores = new LinkedHashMap<>();
        Set<Long> visited = new HashSet<>();
        Queue<NodeDepth> queue = new ArrayDeque<>();
        queue.add(new NodeDepth(entityId, 0, 1.0));
        visited.add(entityId);

        while (!queue.isEmpty()) {
            NodeDepth current = queue.poll();
            if (current.depth() >= maxDepth) {
                continue;
            }
            for (KnowledgeRelation relation : repository.findOutgoingRelations(current.entityId(), minWeight)) {
                long targetId = relation.targetEntityId();
                if (targetId == entityId) {
                    continue;
                }
                double score = current.score() * relation.weight();
                scores.merge(targetId, score, Math::max);
                if (visited.add(targetId)) {
                    queue.add(new NodeDepth(targetId, current.depth() + 1, score));
                }
            }
        }

        Map<Long, KnowledgeEntity> entities = indexById(repository.findEntitiesByIds(scores.keySet()));
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .map(entry -> entities.get(entry.getKey()))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Finds a shortest directed path between two entities.
     */
    public List<KnowledgeRelation> findPath(long sourceId, long targetId) {
        if (sourceId == targetId) {
            return List.of();
        }
        Queue<PathNode> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(new PathNode(sourceId, List.of()));
        visited.add(sourceId);

        while (!queue.isEmpty()) {
            PathNode node = queue.poll();
            if (node.path().size() >= PATH_MAX_DEPTH) {
                continue;
            }
            for (KnowledgeRelation relation : repository.findOutgoingRelations(node.entityId(), 0.0)) {
                List<KnowledgeRelation> nextPath = new ArrayList<>(node.path());
                nextPath.add(relation);
                if (relation.targetEntityId() == targetId) {
                    return nextPath;
                }
                if (visited.add(relation.targetEntityId())) {
                    queue.add(new PathNode(relation.targetEntityId(), List.copyOf(nextPath)));
                }
            }
        }
        return List.of();
    }

    /**
     * Discovers related entities from graph, tag overlap, and vector similarity.
     */
    public List<KnowledgeEntity> discoverRelatedEntities(long entityId, int topK) {
        KnowledgeEntity source = repository.findEntityById(entityId).orElse(null);
        if (source == null || topK <= 0) {
            return List.of();
        }
        Map<Long, ScoredEntity> merged = new LinkedHashMap<>();
        addScores(merged, findRelatedEntities(entityId, 2, 0.5), 1.0);
        addScores(merged, discoverByTags(source), 0.75);
        addScores(merged, discoverBySimilarity(source), 0.65);
        merged.remove(entityId);
        return merged.values().stream()
                .sorted(Comparator.comparingDouble(ScoredEntity::score).reversed())
                .limit(topK)
                .map(ScoredEntity::entity)
                .toList();
    }

    public List<KnowledgeEntity> discoverByTags(KnowledgeEntity entity) {
        Set<String> sourceTags = tagsFromMetadata(entity.metadata());
        if (sourceTags.isEmpty()) {
            return List.of();
        }
        return repository.findAllEntities().stream()
                .filter(candidate -> !candidate.id().equals(entity.id()))
                .filter(candidate -> !java.util.Collections.disjoint(sourceTags, tagsFromMetadata(candidate.metadata())))
                .toList();
    }

    public List<KnowledgeEntity> discoverByGraph(KnowledgeEntity entity) {
        return findRelatedEntities(entity.id(), 2, 0.5);
    }

    public List<KnowledgeEntity> discoverBySimilarity(KnowledgeEntity entity) {
        double[] source = parseVector(entity.embedding());
        if (source.length == 0) {
            return List.of();
        }
        return repository.findAllEntities().stream()
                .filter(candidate -> !candidate.id().equals(entity.id()))
                .map(candidate -> new ScoredEntity(candidate, cosine(source, parseVector(candidate.embedding()))))
                .filter(scored -> scored.score() >= 0.85)
                .sorted(Comparator.comparingDouble(ScoredEntity::score).reversed())
                .map(ScoredEntity::entity)
                .toList();
    }

    /**
     * Formats compact graph context lines for prompt injection.
     */
    public List<String> contextForQuestion(String question, int limit) {
        if (!StringUtils.hasText(question) || limit <= 0) {
            return List.of();
        }
        List<KnowledgeEntity> seeds = repository.searchByNameOrAlias(question, 5);
        if (seeds.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (KnowledgeEntity seed : seeds) {
            for (KnowledgeRelation relation : repository.findOutgoingRelations(seed.id(), 0.5)) {
                KnowledgeEntity target = repository.findEntityById(relation.targetEntityId()).orElse(null);
                if (target == null) {
                    continue;
                }
                lines.add("%s -> %s (%s, weight=%.2f): %s".formatted(
                        seed.name(),
                        target.name(),
                        relation.relationType(),
                        relation.weight(),
                        StringUtils.hasText(target.description()) ? target.description() : target.type()));
                if (lines.size() >= limit) {
                    return lines;
                }
            }
        }
        return lines;
    }

    /**
     * Ingests content analysis into the cross-post graph.
     */
    public void ingestPostAnalysis(long postId, String title, String body, ContentAnalysis analysis) {
        List<KnowledgeEntity> entities = new ArrayList<>();
        if (analysis != null && analysis.entities() != null) {
            for (Entity entity : analysis.entities()) {
                if (entity == null || !StringUtils.hasText(entity.name()) || entity.confidence() < 0.55) {
                    continue;
                }
                entities.add(addEntity(entity.name(), mapEntityType(entity.category()), "from post " + postId));
            }
        }
        for (int i = 0; i < entities.size(); i++) {
            for (int j = i + 1; j < entities.size(); j++) {
                addRelation(entities.get(i).id(), entities.get(j).id(), KnowledgeRelationType.RELATED_TO, 0.7);
                addRelation(entities.get(j).id(), entities.get(i).id(), KnowledgeRelationType.RELATED_TO, 0.7);
            }
        }
    }

    /**
     * Ingests extracted graph entities and relations.
     */
    public void ingestExtraction(KnowledgeExtractionResult extraction) {
        if (extraction == null || extraction.entities() == null || extraction.entities().isEmpty()) {
            return;
        }
        Map<String, KnowledgeEntity> byName = new LinkedHashMap<>();
        for (ExtractedKnowledgeEntity extracted : extraction.entities()) {
            KnowledgeEntity entity = addEntity(
                    extracted.name(),
                    extracted.type(),
                    extracted.description(),
                    extracted.aliases());
            byName.putIfAbsent(extracted.name(), entity);
        }
        if (extraction.relations() == null) {
            return;
        }
        for (ExtractedKnowledgeRelation relation : extraction.relations()) {
            KnowledgeEntity source = byName.get(relation.sourceName());
            KnowledgeEntity target = byName.get(relation.targetName());
            if (source == null || target == null || source.id().equals(target.id())) {
                continue;
            }
            addRelation(source.id(), target.id(), relation.relationType(), relation.weight());
        }
    }

    private KnowledgeEntityType mapEntityType(String category) {
        if (!StringUtils.hasText(category)) {
            return KnowledgeEntityType.CONCEPT;
        }
        String text = category.toLowerCase();
        if (text.contains("角色") || text.contains("person") || text.contains("人物")) {
            return KnowledgeEntityType.PERSON;
        }
        if (text.contains("作品") || text.contains("work") || text.contains("动漫") || text.contains("动画")) {
            return KnowledgeEntityType.WORK;
        }
        if (text.contains("tag") || text.contains("标签")) {
            return KnowledgeEntityType.TAG;
        }
        return KnowledgeEntityType.CONCEPT;
    }

    private void addScores(Map<Long, ScoredEntity> merged, List<KnowledgeEntity> entities, double score) {
        for (KnowledgeEntity entity : entities) {
            merged.merge(entity.id(), new ScoredEntity(entity, score),
                    (left, right) -> left.score() >= right.score() ? left : right);
        }
    }

    private Set<String> tagsFromMetadata(String metadata) {
        if (!StringUtils.hasText(metadata) || "{}".equals(metadata.trim())) {
            return Set.of();
        }
        try {
            Map<String, Object> data = objectMapper.readValue(metadata, new TypeReference<>() {
            });
            Object rawTags = data.get("tags");
            if (!(rawTags instanceof Collection<?> values)) {
                return Set.of();
            }
            Set<String> tags = new LinkedHashSet<>();
            for (Object value : values) {
                if (value != null && StringUtils.hasText(String.valueOf(value))) {
                    tags.add(String.valueOf(value).trim().toLowerCase());
                }
            }
            return tags;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse knowledge entity metadata: {}", e.getMessage());
            return Set.of();
        }
    }

    private static List<String> mergeAliases(List<String> existing, List<String> incoming) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existing != null) {
            merged.addAll(existing);
        }
        if (incoming != null) {
            for (String alias : incoming) {
                if (StringUtils.hasText(alias)) {
                    merged.add(alias.trim());
                }
            }
        }
        return List.copyOf(merged);
    }

    private static double clampWeight(double weight) {
        return Math.max(0.0, Math.min(1.0, weight));
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim();
    }

    private static Map<Long, KnowledgeEntity> indexById(List<KnowledgeEntity> entities) {
        Map<Long, KnowledgeEntity> indexed = new HashMap<>();
        for (KnowledgeEntity entity : entities) {
            indexed.put(entity.id(), entity);
        }
        return indexed;
    }

    private static double[] parseVector(String vector) {
        if (!StringUtils.hasText(vector)) {
            return new double[0];
        }
        String cleaned = vector.replace("[", "").replace("]", "");
        String[] parts = cleaned.split(",");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                return new double[0];
            }
        }
        return values;
    }

    private static double cosine(double[] a, double[] b) {
        if (a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record NodeDepth(long entityId, int depth, double score) {
    }

    private record PathNode(long entityId, List<KnowledgeRelation> path) {
    }

    private record ScoredEntity(KnowledgeEntity entity, double score) {
    }
}
