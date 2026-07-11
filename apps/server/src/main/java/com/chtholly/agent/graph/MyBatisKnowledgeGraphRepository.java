package com.chtholly.agent.graph;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MyBatis implementation of the knowledge graph repository.
 */
@Repository
@ConditionalOnProperty(prefix = "agent.extensions.graph", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MyBatisKnowledgeGraphRepository implements KnowledgeGraphRepository {

    private final KnowledgeGraphMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisKnowledgeGraphRepository(KnowledgeGraphMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<KnowledgeEntity> findEntityByNameAndType(String name, KnowledgeEntityType type) {
        return Optional.ofNullable(mapper.findEntityByNameAndType(name, type.name())).map(this::toEntity);
    }

    @Override
    public Optional<KnowledgeEntity> findEntityById(long id) {
        return Optional.ofNullable(mapper.findEntityById(id)).map(this::toEntity);
    }

    @Override
    public KnowledgeEntity saveEntity(KnowledgeEntity entity) {
        KnowledgeEntityRow row = toRow(entity);
        if (mapper.findEntityById(entity.id()) == null) {
            mapper.insertEntity(row);
        } else {
            mapper.updateEntity(row);
        }
        return entity;
    }

    @Override
    public KnowledgeRelation saveRelation(KnowledgeRelation relation) {
        mapper.insertRelation(toRow(relation));
        return relation;
    }

    @Override
    public List<KnowledgeRelation> findOutgoingRelations(long sourceEntityId, double minWeight) {
        return mapper.findOutgoingRelations(sourceEntityId, minWeight).stream().map(this::toRelation).toList();
    }

    @Override
    public List<KnowledgeEntity> findEntitiesByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return mapper.findEntitiesByIds(ids).stream().map(this::toEntity).toList();
    }

    @Override
    public List<KnowledgeEntity> searchByNameOrAlias(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }
        return mapper.searchByNameOrAlias(query.trim(), Math.min(limit, 20)).stream().map(this::toEntity).toList();
    }

    @Override
    public List<KnowledgeEntity> findAllEntities() {
        return mapper.findAllEntities().stream().map(this::toEntity).toList();
    }

    private KnowledgeEntity toEntity(KnowledgeEntityRow row) {
        return new KnowledgeEntity(
                row.id(),
                row.name(),
                KnowledgeEntityType.valueOf(row.type()),
                row.description(),
                readAliases(row.aliases()),
                row.embedding(),
                row.metadata(),
                row.createdAt(),
                row.updatedAt());
    }

    private KnowledgeRelation toRelation(KnowledgeRelationRow row) {
        return new KnowledgeRelation(
                row.id(),
                row.sourceEntityId(),
                row.targetEntityId(),
                KnowledgeRelationType.valueOf(row.relationType()),
                row.weight(),
                row.metadata(),
                row.createdAt());
    }

    private KnowledgeEntityRow toRow(KnowledgeEntity entity) {
        return new KnowledgeEntityRow(
                entity.id(),
                entity.name(),
                entity.type().name(),
                entity.description(),
                writeAliases(entity.aliases()),
                entity.embedding(),
                entity.metadata() == null ? "{}" : entity.metadata(),
                entity.createdAt(),
                entity.updatedAt());
    }

    private KnowledgeRelationRow toRow(KnowledgeRelation relation) {
        return new KnowledgeRelationRow(
                relation.id(),
                relation.sourceEntityId(),
                relation.targetEntityId(),
                relation.relationType().name(),
                relation.weight(),
                relation.metadata() == null ? "{}" : relation.metadata(),
                relation.createdAt());
    }

    private List<String> readAliases(String aliasesJson) {
        if (aliasesJson == null || aliasesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(aliasesJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String writeAliases(List<String> aliases) {
        try {
            return objectMapper.writeValueAsString(aliases == null ? List.of() : aliases);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
