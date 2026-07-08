package com.chtholly.agent.graph;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * MyBatis mapper for knowledge graph adjacency tables.
 */
@Mapper
public interface KnowledgeGraphMapper {

    KnowledgeEntityRow findEntityByNameAndType(@Param("name") String name, @Param("type") String type);

    KnowledgeEntityRow findEntityById(@Param("id") long id);

    List<KnowledgeEntityRow> findEntitiesByIds(@Param("ids") Collection<Long> ids);

    List<KnowledgeEntityRow> searchByNameOrAlias(@Param("query") String query, @Param("limit") int limit);

    List<KnowledgeEntityRow> findAllEntities();

    int insertEntity(KnowledgeEntityRow entity);

    int updateEntity(KnowledgeEntityRow entity);

    List<KnowledgeRelationRow> findOutgoingRelations(@Param("sourceEntityId") long sourceEntityId,
                                                     @Param("minWeight") double minWeight);

    int insertRelation(KnowledgeRelationRow relation);
}
