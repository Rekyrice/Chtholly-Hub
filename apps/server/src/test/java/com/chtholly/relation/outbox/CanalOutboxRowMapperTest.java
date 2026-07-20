package com.chtholly.relation.outbox;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.chtholly.common.util.OutboxMessageUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract for preserving Outbox identity in Canal-equivalent Kafka rows.
 */
class CanalOutboxRowMapperTest {

    private final CanalOutboxRowMapper mapper = new CanalOutboxRowMapper(new ObjectMapper());

    @Test
    void mapsStableOutboxContractFromAfterColumns() {
        String payload = "{\"entity\":\"post\",\"op\":\"upsert\",\"id\":7}";
        CanalEntry.RowData rowData = CanalEntry.RowData.newBuilder()
                .addAfterColumns(column("id", "42"))
                .addAfterColumns(column("aggregate_type", "post"))
                .addAfterColumns(column("type", "PostPublished"))
                .addAfterColumns(column("payload", payload))
                .build();

        JsonNode row = mapper.toJson(rowData);

        assertThat(OutboxMessageUtil.extractEventId(row)).isEqualTo(42L);
        assertThat(row.path("aggregate_type").asText()).isEqualTo("post");
        assertThat(row.path("type").asText()).isEqualTo("PostPublished");
        assertThat(row.path("payload").asText()).isEqualTo(payload);
    }

    private CanalEntry.Column column(String name, String value) {
        return CanalEntry.Column.newBuilder().setName(name).setValue(value).build();
    }
}
