package com.chtholly.post.service.impl;

import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.relation.outbox.OutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostOutboxWriterTest {

    @Test
    void returnsThePersistedEventIdForTheAfterCommitProjectionPath() {
        OutboxMapper mapper = mock(OutboxMapper.class);
        SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
        when(idGenerator.nextId()).thenReturn(101L);
        when(mapper.insert(eq(101L), eq("post"), eq(42L), eq("PostPublished"), anyString()))
                .thenReturn(1);
        PostOutboxWriter writer = new PostOutboxWriter(mapper, new ObjectMapper(), idGenerator);

        long eventId = writer.write(42L, "PostPublished", "upsert");

        assertThat(eventId).isEqualTo(101L);
        verify(mapper).insert(eq(101L), eq("post"), eq(42L), eq("PostPublished"), anyString());
    }
}
