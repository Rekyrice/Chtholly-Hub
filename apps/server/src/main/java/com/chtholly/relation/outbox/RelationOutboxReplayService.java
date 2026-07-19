package com.chtholly.relation.outbox;

import com.chtholly.relation.event.RelationEvent;
import com.chtholly.relation.processor.RelationEventProcessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** Explicitly replays existing relation Outbox rows through the terminal projection logic. */
@Service
public class RelationOutboxReplayService {

    static final int MAX_REPLAY_ROWS = 1000;
    private static final Set<String> SUPPORTED_TYPES = Set.of("FollowCreated", "FollowCanceled");

    private final OutboxMapper outboxMapper;
    private final RelationEventProcessor processor;
    private final ObjectMapper objectMapper;

    public RelationOutboxReplayService(OutboxMapper outboxMapper,
                                       RelationEventProcessor processor,
                                       ObjectMapper objectMapper) {
        this.outboxMapper = outboxMapper;
        this.processor = processor;
        this.objectMapper = objectMapper;
    }

    public int replayId(long outboxId) {
        requirePositive(outboxId, "outboxId");
        OutboxMapper.RelationReplayRow row = outboxMapper.findRelationReplayRow(outboxId);
        if (row == null) {
            return 0;
        }
        project(row);
        return 1;
    }

    public int replayRange(long fromId, long toId) {
        requirePositive(fromId, "fromId");
        requirePositive(toId, "toId");
        if (fromId > toId) {
            throw new IllegalArgumentException("fromId must not exceed toId");
        }
        List<OutboxMapper.RelationReplayRow> rows = outboxMapper.listRelationReplayRows(
                fromId, toId, MAX_REPLAY_ROWS + 1);
        if (rows.size() > MAX_REPLAY_ROWS) {
            throw new IllegalArgumentException("Relation Outbox replay range exceeds 1000 rows");
        }
        rows.forEach(this::project);
        return rows.size();
    }

    private void project(OutboxMapper.RelationReplayRow row) {
        if (!SUPPORTED_TYPES.contains(row.type())) {
            throw new IllegalArgumentException("Unsupported relation Outbox type: " + row.type());
        }
        final RelationEvent event;
        try {
            event = objectMapper.readValue(row.payload(), RelationEvent.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid relation Outbox payload for ID " + row.id(), failure);
        }
        if (!row.type().equals(event.type())) {
            throw new IllegalArgumentException("Relation Outbox type does not match payload type for ID " + row.id());
        }
        processor.process(event);
    }

    private void requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
