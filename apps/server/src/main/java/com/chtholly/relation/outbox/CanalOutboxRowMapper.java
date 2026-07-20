package com.chtholly.relation.outbox;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps one Canal Outbox row to the stable Kafka row contract.
 */
@Component
@RequiredArgsConstructor
public class CanalOutboxRowMapper {

    private final ObjectMapper objectMapper;

    /**
     * Preserves the fields needed for consumer routing, validation and idempotency.
     *
     * @param rowData Canal row data after an insert or update
     * @return stable Kafka row containing ID, aggregate type, event type and payload
     */
    public ObjectNode toJson(CanalEntry.RowData rowData) {
        ObjectNode row = objectMapper.createObjectNode();
        for (CanalEntry.Column column : rowData.getAfterColumnsList()) {
            String name = column.getName();
            if ("id".equalsIgnoreCase(name)) {
                row.put("id", column.getValue());
            } else if ("aggregate_type".equalsIgnoreCase(name)) {
                row.put("aggregate_type", column.getValue());
            } else if ("type".equalsIgnoreCase(name)) {
                row.put("type", column.getValue());
            } else if ("payload".equalsIgnoreCase(name)) {
                row.put("payload", column.getValue());
            }
        }
        return row;
    }
}
