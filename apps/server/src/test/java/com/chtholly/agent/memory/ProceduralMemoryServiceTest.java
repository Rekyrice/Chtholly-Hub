package com.chtholly.agent.memory;

import com.chtholly.agent.learning.Insight;
import com.chtholly.agent.learning.Insight.InsightState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProceduralMemoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-03T01:00:00Z");

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private ObjectMapper objectMapper;
    private ProceduralMemoryService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(redis.opsForHash()).thenReturn(hashOps);
        service = new ProceduralMemoryService(redis, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storeRuleWritesStructuredInsightToProceduralHash() throws Exception {
        service.storeRule(42L, "Answer character questions with role names first");

        ArgumentCaptor<String> fieldCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq("agent:procedural:42"), fieldCaptor.capture(), valueCaptor.capture());

        Insight saved = objectMapper.readValue(valueCaptor.getValue(), Insight.class);
        assertThat(fieldCaptor.getValue()).hasSize(8);
        assertThat(saved.id()).isEqualTo(fieldCaptor.getValue());
        assertThat(saved.text()).isEqualTo("Answer character questions with role names first");
        assertThat(saved.createdAt()).isEqualTo(NOW);
        assertThat(saved.lastUsedAt()).isEqualTo(NOW);
        assertThat(saved.useCount()).isZero();
        assertThat(saved.confidenceScore()).isEqualTo(0.5);
        assertThat(saved.state()).isEqualTo(InsightState.ACTIVE);
    }

    @Test
    void getTopRulesReturnsActiveRulesSortedByUseCountWithinCharacterBudget() throws Exception {
        when(hashOps.entries("agent:procedural:42")).thenReturn(Map.of(
                "a", objectMapper.writeValueAsString(insight("a", "first high use", 10, 0.9, InsightState.ACTIVE)),
                "b", objectMapper.writeValueAsString(insight("b", "second", 8, 0.8, InsightState.ACTIVE)),
                "c", objectMapper.writeValueAsString(insight("c", "third", 6, 0.8, InsightState.ACTIVE)),
                "d", objectMapper.writeValueAsString(insight("d", "stale rule", 99, 0.8, InsightState.STALE)),
                "e", objectMapper.writeValueAsString(insight("e", "too long for the remaining budget", 5, 0.8, InsightState.ACTIVE))
        ));

        assertThat(service.getTopRules(42L, 5, 25))
                .containsExactly("first high use", "second", "third");
    }

    @Test
    void recordRuleUsageIncrementsUseCountAndConfidence() throws Exception {
        Insight existing = insight("rule0001", "Use concise answers", 2, 0.95, InsightState.ACTIVE);
        when(hashOps.get("agent:procedural:42", "rule0001")).thenReturn(objectMapper.writeValueAsString(existing));

        service.recordRuleUsage(42L, "rule0001");

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq("agent:procedural:42"), eq("rule0001"), valueCaptor.capture());

        Insight updated = objectMapper.readValue(valueCaptor.getValue(), Insight.class);
        assertThat(updated.useCount()).isEqualTo(3);
        assertThat(updated.lastUsedAt()).isEqualTo(NOW);
        assertThat(updated.confidenceScore()).isEqualTo(1.0);
        assertThat(updated.state()).isEqualTo(InsightState.ACTIVE);
    }

    @Test
    void recordNegativeFeedbackReducesConfidenceAndMarksLowConfidenceAsStale() throws Exception {
        Insight existing = insight("rule0001", "Use concise answers", 2, 0.3, InsightState.ACTIVE);
        when(hashOps.get("agent:procedural:42", "rule0001")).thenReturn(objectMapper.writeValueAsString(existing));

        service.recordNegativeFeedback(42L, "rule0001");

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq("agent:procedural:42"), eq("rule0001"), valueCaptor.capture());

        Insight updated = objectMapper.readValue(valueCaptor.getValue(), Insight.class);
        assertThat(updated.confidenceScore()).isLessThan(0.2);
        assertThat(updated.state()).isEqualTo(InsightState.STALE);
        assertThat(updated.useCount()).isEqualTo(2);
        assertThat(updated.lastUsedAt()).isEqualTo(existing.lastUsedAt());
    }

    @Test
    void usageAndFeedbackIgnoreMissingRule() {
        when(hashOps.get("agent:procedural:42", "missing")).thenReturn(null);

        service.recordRuleUsage(42L, "missing");
        service.recordNegativeFeedback(42L, "missing");

        verify(hashOps, never()).put(eq("agent:procedural:42"), anyString(), anyString());
    }

    private Insight insight(String id, String text, int useCount, double confidence, InsightState state) {
        return new Insight(id, text, NOW.minusSeconds(60), NOW.minusSeconds(30), useCount, confidence, state);
    }
}
