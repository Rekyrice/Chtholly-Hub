package com.chtholly.agent.graph;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for graph nodes and edges.
 */
public interface KnowledgeGraphRepository {

    Optional<KnowledgeEntity> findEntityByNameAndType(String name, KnowledgeEntityType type);

    Optional<KnowledgeEntity> findEntityById(long id);

    KnowledgeEntity saveEntity(KnowledgeEntity entity);

    KnowledgeRelation saveRelation(KnowledgeRelation relation);

    List<KnowledgeRelation> findOutgoingRelations(long sourceEntityId, double minWeight);

    List<KnowledgeEntity> findEntitiesByIds(Collection<Long> ids);

    List<KnowledgeEntity> searchByNameOrAlias(String query, int limit);

    List<KnowledgeEntity> findAllEntities();
}
