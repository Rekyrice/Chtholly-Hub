package com.chtholly.agent.learning;

import com.chtholly.agent.memory.AgentTurn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InsightServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-03T01:00:00Z");

    private ObjectMapper objectMapper;
    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private ZSetOperations<String, String> zSetOps;
    private SetOperations<String, String> setOps;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        zSetOps = mock(ZSetOperations.class);
        setOps = mock(SetOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        when(redis.opsForZSet()).thenReturn(zSetOps);
        when(redis.opsForSet()).thenReturn(setOps);
    }

    @Test
    void reflectOnConversationStoresGeneratedRulesAsPersonalAndGlobalCandidates() {
        InsightService service = service(prompt -> List.of(
                "Answer character questions with role names first",
                "Include score and airing date when users ask for ratings",
                "   "));

        service.reflectOnConversation(42L, longConversation());

        verify(hashOps).put(eq("insights:personal:42"), anyString(), org.mockito.ArgumentMatchers.contains("Answer character questions"));
        verify(hashOps).put(eq("insights:personal:42"), anyString(), org.mockito.ArgumentMatchers.contains("Include score"));
        verify(zSetOps, org.mockito.Mockito.times(2)).add(eq("insights:global:candidate"), anyString(), anyDouble());
    }

    @Test
    void reflectOnConversationSkipsShortConversation() {
        AtomicInteger calls = new AtomicInteger();
        InsightService service = service(prompt -> {
            calls.incrementAndGet();
            return List.of("unused");
        });

        service.reflectOnConversation(42L, List.of(AgentTurn.user("too short")));

        assertThat(calls).hasValue(0);
        verify(hashOps, never()).put(anyString(), anyString(), anyString());
        verify(zSetOps, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void reflectPromptIncludesExistingPersonalAndGlobalInsights() throws Exception {
        AtomicReference<String> promptRef = new AtomicReference<>();
        InsightService service = service(prompt -> {
            promptRef.set(prompt);
            return List.of();
        });
        when(hashOps.entries("insights:global")).thenReturn(Map.of(
                "global1", json(insight("global1", "Shared global rule", 0.9, NOW))));
        when(hashOps.entries("insights:personal:42")).thenReturn(Map.of(
                "personal1", json(insight("personal1", "Existing personal rule", 0.7, NOW))));

        service.reflectOnConversation(42L, longConversation());

        assertThat(promptRef.get())
                .contains("Shared global rule")
                .contains("Existing personal rule");
    }

    @Test
    void curateInsightsPromotesSameSemanticCandidateFromFiveUsersToGlobalInsight() throws Exception {
        InsightService service = service(prompt -> List.of());
        Set<String> candidates = Set.of(
                candidateJson(1L, "Answer rating questions with score and date"),
                candidateJson(2L, "Answer rating questions with score and date"),
                candidateJson(3L, "Answer rating questions with score and date"),
                candidateJson(4L, "Answer rating questions with score and date"),
                candidateJson(5L, "Answer rating questions with score and date")
        );
        when(zSetOps.rangeByScore("insights:global:candidate", 0, Double.MAX_VALUE)).thenReturn(candidates);
        for (long userId = 1; userId <= 5; userId++) {
            when(hashOps.entries("insights:personal:" + userId)).thenReturn(Map.of(
                    "personal" + userId, json(insight("personal" + userId, "Answer rating questions with score and date", 0.5, NOW))));
        }

        service.curateInsights();

        ArgumentCaptor<String> globalValue = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq("insights:global"), anyString(), globalValue.capture());
        Insight promoted = objectMapper.readValue(globalValue.getValue(), Insight.class);
        assertThat(promoted.text()).isEqualTo("Answer rating questions with score and date");
        assertThat(promoted.confidenceScore()).isGreaterThanOrEqualTo(0.8);
        verify(hashOps).put(eq("insights:global:meta:" + promoted.id()), eq("count"), eq("5"));
        verify(hashOps).put(eq("insights:global:meta:" + promoted.id()), eq("sharedBy"), org.mockito.ArgumentMatchers.contains("1"));
        verify(zSetOps).remove(eq("insights:global:candidate"), org.mockito.ArgumentMatchers.any(Object[].class));
    }

    @Test
    void personalInsightsAreIsolatedPerUser() throws Exception {
        InsightService service = service(prompt -> List.of());
        when(hashOps.entries("insights:global")).thenReturn(Map.of());
        when(hashOps.entries("insights:personal:1")).thenReturn(Map.of(
                "a", json(insight("a", "User A only rule", 0.8, NOW))));
        when(hashOps.entries("insights:personal:2")).thenReturn(Map.of(
                "b", json(insight("b", "User B only rule", 0.8, NOW))));

        assertThat(service.getInsightsForUser(1L))
                .extracting(InsightService.UserInsight::text)
                .containsExactly("User A only rule");
        assertThat(service.getInsightsForUser(2L))
                .extracting(InsightService.UserInsight::text)
                .containsExactly("User B only rule");
    }

    @Test
    void getInsightsForUserMergesGlobalAndPersonalSortedByConfidence() throws Exception {
        InsightService service = service(prompt -> List.of());
        when(hashOps.entries("insights:global")).thenReturn(Map.of(
                "g", json(insight("g", "Global rule", 0.9, NOW))));
        when(hashOps.entries("insights:personal:42")).thenReturn(Map.of(
                "p", json(insight("p", "Personal rule", 0.7, NOW))));

        assertThat(service.getInsightsForUser(42L))
                .extracting(InsightService.UserInsight::source)
                .containsExactly("global", "personal");
        assertThat(service.getInsightTextsForUser(42L, 5, 500))
                .containsExactly("Global rule", "Personal rule");
    }

    @Test
    void storePersonalInsightKeepsAtMostFifteenByConfidenceAndRecency() throws Exception {
        InsightService service = service(prompt -> List.of());
        Map<Object, Object> existing = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            existing.put("old" + i, json(insight("old" + i, "old rule " + i, 0.1, NOW.minusSeconds(31L * 24 * 3600))));
        }
        when(hashOps.entries("insights:personal:42")).thenReturn(existing);

        service.storePersonalInsight(42L, "fresh important rule");

        verify(hashOps, org.mockito.Mockito.atLeast(5)).delete(eq("insights:personal:42"), org.mockito.ArgumentMatchers.any());
    }

    private InsightService service(Function<String, List<String>> generator) {
        return new InsightService(
                objectMapper,
                generator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                redis);
    }

    private Insight insight(String id, String text, double confidence, Instant lastUsedAt) {
        return new Insight(id, text, NOW.minusSeconds(60), lastUsedAt, 0, confidence, Insight.InsightState.ACTIVE);
    }

    private String json(Insight insight) throws Exception {
        return objectMapper.writeValueAsString(insight);
    }

    private String candidateJson(long userId, String text) throws Exception {
        return objectMapper.writeValueAsString(new InsightService.GlobalInsightCandidate(
                "candidate" + userId,
                text,
                userId,
                InsightService.semanticHash(text),
                NOW));
    }

    private List<AgentTurn> longConversation() {
        return List.of(
                AgentTurn.user("Which characters are in it?"),
                AgentTurn.assistant("List main characters first."),
                AgentTurn.user("What about voice actors?"),
                AgentTurn.assistant("Add voice actor info."),
                AgentTurn.user("What is the rating?"),
                AgentTurn.assistant("Add score and airing details.")
        );
    }
}
