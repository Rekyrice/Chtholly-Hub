-- V20__knowledge_graph.sql: Agent 知识图谱实体与关系表

CREATE TABLE IF NOT EXISTS knowledge_entities (
    id BIGINT UNSIGNED NOT NULL,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(32) NOT NULL COMMENT 'PERSON / WORK / CONCEPT / TAG',
    description TEXT NULL,
    aliases JSON NULL,
    embedding TEXT NULL COMMENT 'Serialized vector text for later similarity calculation',
    metadata JSON NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_entities_name_type (name, type),
    KEY idx_knowledge_entities_type (type),
    KEY idx_knowledge_entities_updated_at (updated_at),
    FULLTEXT KEY ft_knowledge_entities_name_desc (name, description)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_relations (
    id BIGINT UNSIGNED NOT NULL,
    source_entity_id BIGINT UNSIGNED NOT NULL,
    target_entity_id BIGINT UNSIGNED NOT NULL,
    relation_type VARCHAR(32) NOT NULL COMMENT 'APPEARS_IN / RELATED_TO / RECOMMENDS / CREATED_BY',
    weight DOUBLE NOT NULL DEFAULT 1.0,
    metadata JSON NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_knowledge_relations_edge (source_entity_id, target_entity_id, relation_type),
    KEY idx_knowledge_relations_source_weight (source_entity_id, weight),
    KEY idx_knowledge_relations_target (target_entity_id),
    CONSTRAINT fk_knowledge_relations_source FOREIGN KEY (source_entity_id) REFERENCES knowledge_entities(id),
    CONSTRAINT fk_knowledge_relations_target FOREIGN KEY (target_entity_id) REFERENCES knowledge_entities(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
