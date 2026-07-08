package com.chtholly.agent.graph;

import com.chtholly.post.id.SnowflakeIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphServiceTest {

    private KnowledgeGraphService service;
    private InMemoryKnowledgeGraphRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryKnowledgeGraphRepository();
        service = new KnowledgeGraphService(
                repository,
                new SnowflakeIdGenerator(1, 3),
                new ObjectMapper().findAndRegisterModules(),
                () -> Instant.parse("2026-07-08T12:00:00Z"));
    }

    @Test
    void addEntityDeduplicatesByNameAndTypeAndMergesAliases() {
        KnowledgeEntity first = service.addEntity("Frieren", KnowledgeEntityType.WORK, "time story");
        KnowledgeEntity second = service.addEntity("Frieren", KnowledgeEntityType.WORK, "quiet journey", List.of("葬送的芙莉莲"));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.description()).isEqualTo("quiet journey");
        assertThat(second.aliases()).containsExactly("葬送的芙莉莲");
        assertThat(repository.entities).hasSize(1);
    }

    @Test
    void findRelatedEntitiesWalksTwoHopsAndFiltersWeakRelations() {
        KnowledgeEntity frieren = service.addEntity("Frieren", KnowledgeEntityType.WORK, "work");
        KnowledgeEntity time = service.addEntity("time", KnowledgeEntityType.CONCEPT, "concept");
        KnowledgeEntity memory = service.addEntity("memory", KnowledgeEntityType.CONCEPT, "concept");
        KnowledgeEntity noise = service.addEntity("noise", KnowledgeEntityType.TAG, "tag");
        service.addRelation(frieren.id(), time.id(), KnowledgeRelationType.RELATED_TO, 0.9);
        service.addRelation(time.id(), memory.id(), KnowledgeRelationType.RELATED_TO, 0.8);
        service.addRelation(frieren.id(), noise.id(), KnowledgeRelationType.RELATED_TO, 0.2);

        List<KnowledgeEntity> related = service.findRelatedEntities(frieren.id(), 2, 0.5);

        assertThat(related).extracting(KnowledgeEntity::name).containsExactly("time", "memory");
    }

    @Test
    void findPathReturnsShortestRelationPath() {
        KnowledgeEntity a = service.addEntity("A", KnowledgeEntityType.CONCEPT, "");
        KnowledgeEntity b = service.addEntity("B", KnowledgeEntityType.CONCEPT, "");
        KnowledgeEntity c = service.addEntity("C", KnowledgeEntityType.CONCEPT, "");
        service.addRelation(a.id(), b.id(), KnowledgeRelationType.RELATED_TO, 0.9);
        service.addRelation(b.id(), c.id(), KnowledgeRelationType.RELATED_TO, 0.9);

        List<KnowledgeRelation> path = service.findPath(a.id(), c.id());

        assertThat(path).hasSize(2);
        assertThat(path).extracting(KnowledgeRelation::sourceEntityId).containsExactly(a.id(), b.id());
        assertThat(path).extracting(KnowledgeRelation::targetEntityId).containsExactly(b.id(), c.id());
    }

    @Test
    void discoverRelatedEntitiesMergesGraphTagsAndVectorSimilarity() {
        KnowledgeEntity source = service.addEntity("Frieren", KnowledgeEntityType.WORK, "work");
        repository.saveEntity(source.withMetadata("{\"tags\":[\"time\",\"healing\"]}").withEmbedding("1,0,0"));
        KnowledgeEntity graph = service.addEntity("Himmel", KnowledgeEntityType.PERSON, "person");
        KnowledgeEntity tag = service.addEntity("Natsume", KnowledgeEntityType.WORK, "work");
        repository.saveEntity(tag.withMetadata("{\"tags\":[\"healing\"]}"));
        KnowledgeEntity similar = service.addEntity("Violet", KnowledgeEntityType.WORK, "work");
        repository.saveEntity(similar.withEmbedding("0.95,0.1,0"));
        service.addRelation(source.id(), graph.id(), KnowledgeRelationType.RELATED_TO, 0.9);

        List<KnowledgeEntity> related = service.discoverRelatedEntities(source.id(), 5);

        assertThat(related).extracting(KnowledgeEntity::name)
                .contains("Himmel", "Natsume", "Violet");
    }

    @Test
    void contextForQuestionFormatsHighConfidenceAssociations() {
        KnowledgeEntity frieren = service.addEntity("Frieren", KnowledgeEntityType.WORK, "time journey", List.of("葬送的芙莉莲"));
        KnowledgeEntity time = service.addEntity("time", KnowledgeEntityType.CONCEPT, "time and memory");
        service.addRelation(frieren.id(), time.id(), KnowledgeRelationType.RELATED_TO, 0.9);

        List<String> context = service.contextForQuestion("聊聊葬送的芙莉莲和时间", 3);

        assertThat(context).anySatisfy(line -> assertThat(line)
                .contains("Frieren")
                .contains("time")
                .contains("RELATED_TO"));
    }

    private static final class InMemoryKnowledgeGraphRepository implements KnowledgeGraphRepository {
        private final Map<Long, KnowledgeEntity> entities = new LinkedHashMap<>();
        private final Map<Long, KnowledgeRelation> relations = new LinkedHashMap<>();
        private long relationId = 1000;

        @Override
        public Optional<KnowledgeEntity> findEntityByNameAndType(String name, KnowledgeEntityType type) {
            return entities.values().stream()
                    .filter(entity -> entity.name().equalsIgnoreCase(name) && entity.type() == type)
                    .findFirst();
        }

        @Override
        public Optional<KnowledgeEntity> findEntityById(long id) {
            return Optional.ofNullable(entities.get(id));
        }

        @Override
        public KnowledgeEntity saveEntity(KnowledgeEntity entity) {
            entities.put(entity.id(), entity);
            return entity;
        }

        @Override
        public KnowledgeRelation saveRelation(KnowledgeRelation relation) {
            KnowledgeRelation stored = relation.id() == null
                    ? relation.withId(relationId++)
                    : relation;
            relations.put(stored.id(), stored);
            return stored;
        }

        @Override
        public List<KnowledgeRelation> findOutgoingRelations(long sourceEntityId, double minWeight) {
            return relations.values().stream()
                    .filter(relation -> relation.sourceEntityId() == sourceEntityId)
                    .filter(relation -> relation.weight() >= minWeight)
                    .sorted(Comparator.comparingDouble(KnowledgeRelation::weight).reversed())
                    .toList();
        }

        @Override
        public List<KnowledgeEntity> findEntitiesByIds(Collection<Long> ids) {
            return ids.stream().map(entities::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public List<KnowledgeEntity> searchByNameOrAlias(String query, int limit) {
            String q = query.toLowerCase();
            return entities.values().stream()
                    .filter(entity -> entity.name().toLowerCase().contains(q)
                            || entity.aliases().stream().anyMatch(alias -> query.contains(alias) || alias.toLowerCase().contains(q)))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<KnowledgeEntity> findAllEntities() {
            return new ArrayList<>(entities.values());
        }
    }
}
