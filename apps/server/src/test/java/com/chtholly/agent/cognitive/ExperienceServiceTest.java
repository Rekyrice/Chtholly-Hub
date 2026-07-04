package com.chtholly.agent.cognitive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperienceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-04T01:00:00Z");

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;

    private ObjectMapper objectMapper;
    private ExperienceService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(redis.opsForList()).thenReturn(listOps);
        service = new ExperienceService(redis, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storeExperiencesPushesJsonAndKeepsRecentFifty() throws Exception {
        Observation observation = new Observation("她在文章里写到时间的重量。", 0.82, NOW, "cognitive-cycle");

        service.storeExperiences(List.of(observation));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOps).rightPush(eq("agent:experiences:global"), payloadCaptor.capture());
        Observation saved = objectMapper.readValue(payloadCaptor.getValue(), Observation.class);
        assertThat(saved).isEqualTo(observation);
        verify(listOps).trim("agent:experiences:global", -50, -1);
    }

    @Test
    void getRecentExperiencesDeserializesNewestFirstAndSkipsBadPayloads() throws Exception {
        Observation older = new Observation("我想再读一遍那篇文章。", 0.8, NOW.minusSeconds(60), "cognitive-cycle");
        Observation newer = new Observation("这句话有点温柔。", 0.9, NOW, "cognitive-cycle");
        when(listOps.range("agent:experiences:global", -3, -1)).thenReturn(List.of(
                objectMapper.writeValueAsString(older),
                "not-json",
                objectMapper.writeValueAsString(newer)
        ));

        assertThat(service.getRecentExperiences(3))
                .containsExactly(newer, older);
    }
}
