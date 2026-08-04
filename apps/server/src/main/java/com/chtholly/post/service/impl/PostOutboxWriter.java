package com.chtholly.post.service.impl;

import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.relation.outbox.OutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Writes post-domain Outbox rows inside the caller's active local transaction. */
@Component
public class PostOutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(PostOutboxWriter.class);

    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Creates the transactional post Outbox writer.
     *
     * @param outboxMapper Outbox persistence mapper
     * @param objectMapper event payload serializer
     * @param idGenerator Outbox ID generator
     */
    public PostOutboxWriter(
            OutboxMapper outboxMapper,
            ObjectMapper objectMapper,
            SnowflakeIdGenerator idGenerator) {
        this.outboxMapper = outboxMapper;
        this.objectMapper = objectMapper;
        this.idGenerator = idGenerator;
    }

    void write(long postId, String eventType, String operation) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(
                    Map.of("entity", "post", "op", operation, "id", postId));
        } catch (JsonProcessingException failure) {
            log.error("Failed to serialize Outbox event for post {}", postId, failure);
            throw new IllegalStateException("Failed to serialize Outbox event for post " + postId, failure);
        }
        outboxMapper.insert(idGenerator.nextId(), "post", postId, eventType, payload);
    }
}
