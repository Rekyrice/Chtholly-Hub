package com.chtholly.relation.outbox;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.awaitility.Awaitility;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import com.google.protobuf.ByteString;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CanalKafkaBridgeTest {

    @Mock
    private KafkaTemplate<String, String> kafka;

    @Mock
    private TaskExecutor taskExecutor;

    @Mock
    private CanalConnector connector;

    private CanalKafkaBridge bridge;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        bridge = new CanalKafkaBridge(
                kafka,
                objectMapper,
                taskExecutor,
                new CanalOutboxRowMapper(objectMapper),
                true,
                "localhost",
                11111,
                "example",
                "canal",
                "",
                "chtholly\\.outbox",
                100,
                50);
    }

    @Test
    void acknowledgesBatchOnlyAfterKafkaPublishIsBrokerConfirmed() throws Exception {
        CompletableFuture<SendResult<String, String>> brokerConfirmation = new CompletableFuture<>();
        when(connector.getWithoutAck(100)).thenReturn(message(7L));
        when(kafka.send(eq(OutboxTopics.CANAL_OUTBOX), eq("42"), anyString()))
                .thenReturn(brokerConfirmation);

        CompletableFuture<Boolean> processing = CompletableFuture.supplyAsync(() -> bridge.processNextBatch(connector));

        Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                verify(kafka).send(eq(OutboxTopics.CANAL_OUTBOX), eq("42"), anyString()));
        verify(connector, never()).ack(7L);

        brokerConfirmation.complete(null);
        assertThat(processing.get(2, TimeUnit.SECONDS)).isTrue();

        InOrder order = inOrder(kafka, connector);
        order.verify(kafka).send(eq(OutboxTopics.CANAL_OUTBOX), eq("42"), anyString());
        order.verify(connector).ack(7L);
        verify(connector, never()).rollback(7L);
    }

    @Test
    void rollsBackBatchWhenKafkaPublishFails() {
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker unavailable"));
        when(connector.getWithoutAck(100)).thenReturn(message(7L));
        when(kafka.send(eq(OutboxTopics.CANAL_OUTBOX), eq("42"), anyString()))
                .thenReturn(failed);

        assertThatThrownBy(() -> bridge.processNextBatch(connector))
                .hasRootCauseMessage("broker unavailable");

        verify(connector).rollback(7L);
        verify(connector, never()).ack(7L);
    }

    @Test
    void rollsBackBatchWhenRowChangeCannotBeParsed() {
        CanalEntry.Entry invalidEntry = CanalEntry.Entry.newBuilder()
                .setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setTableName("outbox"))
                .setStoreValue(ByteString.copyFrom(new byte[]{0}))
                .build();
        when(connector.getWithoutAck(100)).thenReturn(new Message(7L, List.of(invalidEntry)));

        assertThatThrownBy(() -> bridge.processNextBatch(connector))
                .isInstanceOf(IllegalStateException.class);

        verify(connector).rollback(7L);
        verify(connector, never()).ack(7L);
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }

    private Message message(long batchId) {
        CanalEntry.RowData rowData = CanalEntry.RowData.newBuilder()
                .addAfterColumns(column("id", "42"))
                .addAfterColumns(column("aggregate_type", "following"))
                .addAfterColumns(column("type", "FollowCreated"))
                .addAfterColumns(column("payload", "{\"type\":\"FollowCreated\",\"fromUserId\":11,\"toUserId\":22,\"id\":101}"))
                .build();
        CanalEntry.RowChange rowChange = CanalEntry.RowChange.newBuilder()
                .setEventType(CanalEntry.EventType.INSERT)
                .addRowDatas(rowData)
                .build();
        CanalEntry.Entry entry = CanalEntry.Entry.newBuilder()
                .setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setSchemaName("chtholly").setTableName("outbox"))
                .setStoreValue(rowChange.toByteString())
                .build();
        return new Message(batchId, List.of(entry));
    }

    private CanalEntry.Column column(String name, String value) {
        return CanalEntry.Column.newBuilder().setName(name).setValue(value).build();
    }
}
