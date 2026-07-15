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
     * Preserves the event ID used by downstream idempotency guards and the event payload.
     *
     * @param rowData Canal row data after an insert or update
     * @return minimal Kafka row containing {@code id} and {@code payload}
     */
    public ObjectNode toJson(CanalEntry.RowData rowData) {
        ObjectNode row = objectMapper.createObjectNode();
        for (CanalEntry.Column column : rowData.getAfterColumnsList()) {
            if ("id".equalsIgnoreCase(column.getName())) {
                row.put("id", column.getValue());
            } else if ("payload".equalsIgnoreCase(column.getName())) {
                row.put("payload", column.getValue());
            }
        }
        return row;
    }
}
