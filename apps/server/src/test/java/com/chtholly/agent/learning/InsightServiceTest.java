package com.chtholly.agent.learning;

import com.chtholly.agent.learning.Insight.InsightState;
import com.chtholly.agent.memory.AgentTurn;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InsightServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-03T01:00:00Z");

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(redis.opsForHash()).thenReturn(hashOps);
    }

    @Test
    void reflectOnConversationStoresGeneratedInsightsInRedisHash() throws Exception {
        InsightService service = service(prompt -> List.of("回答角色类问题时，先列主要角色再补充声优信息"));
        when(hashOps.entries("agent:insights:42")).thenReturn(Map.of());

        service.reflectOnConversation(42L, longConversation());

        ArgumentCaptor<String> fieldCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOps).put(eq("agent:insights:42"), fieldCaptor.capture(), valueCaptor.capture());
        Insight saved = objectMapper.readValue(valueCaptor.getValue(), Insight.class);
        assertThat(fieldCaptor.getValue()).hasSize(8);
        assertThat(saved.id()).isEqualTo(fieldCaptor.getValue());
        assertThat(saved.text()).isEqualTo("回答角色类问题时，先列主要角色再补充声优信息");
        assertThat(saved.createdAt()).isEqualTo(NOW);
        assertThat(saved.lastUsedAt()).isEqualTo(NOW);
        assertThat(saved.useCount()).isZero();
        assertThat(saved.confidenceScore()).isEqualTo(0.6);
        assertThat(saved.state()).isEqualTo(InsightState.ACTIVE);
    }

    @Test
    void reflectOnConversationSkipsDuplicateInsightText() throws Exception {
        Insight existing = insight("old00001", "回答角色类问题时，先列主要角色再补充声优信息", 3, 0.8, InsightState.ACTIVE);
        InsightService service = service(prompt -> List.of("回答角色类问题时，先列主要角色再补充声优信息"));
        when(hashOps.entries("agent:insights:42")).thenReturn(Map.of(existing.id(), objectMapper.writeValueAsString(existing)));

        service.reflectOnConversation(42L, longConversation());

        verify(hashOps, never()).put(eq("agent:insights:42"), anyString(), anyString());
    }

    @Test
    void getActiveInsightsReturnsTopFiveWithinPromptCharacterBudget() throws Exception {
        InsightService service = service(prompt -> List.of());
        when(hashOps.entries("agent:insights:42")).thenReturn(Map.of(
                "a", objectMapper.writeValueAsString(insight("a", "第一条高频规则", 10, 0.9, InsightState.ACTIVE)),
                "b", objectMapper.writeValueAsString(insight("b", "第二条规则", 8, 0.8, InsightState.ACTIVE)),
                "c", objectMapper.writeValueAsString(insight("c", "第三条规则", 6, 0.8, InsightState.ACTIVE)),
                "d", objectMapper.writeValueAsString(insight("d", "第四条规则", 4, 0.8, InsightState.ACTIVE)),
                "e", objectMapper.writeValueAsString(insight("e", "第五条规则", 2, 0.8, InsightState.ACTIVE)),
                "f", objectMapper.writeValueAsString(insight("f", "不会出现的第六条规则", 1, 0.8, InsightState.ACTIVE)),
                "s", objectMapper.writeValueAsString(insight("s", "过期规则", 99, 0.8, InsightState.STALE))
        ));

        List<String> result = service.getActiveInsights(42L, 5);

        assertThat(result).containsExactly("第一条高频规则", "第二条规则", "第三条规则", "第四条规则", "第五条规则");
        assertThat(result.stream().mapToInt(String::length).sum()).isLessThanOrEqualTo(500);
    }

    @Test
    void curatorMarksOverflowActiveInsightsAsStale() throws Exception {
        List<Insight> existing = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            existing.add(insight("id" + i, "规则 " + i, i, 0.7, InsightState.ACTIVE));
        }
        Map<Object, Object> entries = toEntries(existing);
        InsightService service = service(prompt -> List.of("新的整理触发规则"));
        when(hashOps.entries("agent:insights:42")).thenReturn(entries);

        service.reflectOnConversation(42L, longConversation());

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOps, org.mockito.Mockito.atLeastOnce()).put(eq("agent:insights:42"), anyString(), valueCaptor.capture());
        List<Insight> updated = valueCaptor.getAllValues().stream()
                .map(this::readInsight)
                .toList();
        assertThat(updated).anyMatch(insight -> insight.state() == InsightState.STALE);
    }

    @Test
    void curatorSavesMergedInsightWhenGeneratorReturnsConsolidatedRules() throws Exception {
        List<Insight> existing = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            existing.add(insight("id" + i, "相似规则 " + i, i, 0.7, InsightState.ACTIVE));
        }
        AtomicInteger calls = new AtomicInteger();
        InsightService service = service(prompt ->
                calls.getAndIncrement() == 0
                        ? List.of("新的整理触发规则")
                        : List.of("合并后的角色回答规则"));
        when(hashOps.entries("agent:insights:42")).thenReturn(toEntries(existing));

        service.reflectOnConversation(42L, longConversation());

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOps, org.mockito.Mockito.atLeastOnce()).put(eq("agent:insights:42"), anyString(), valueCaptor.capture());
        List<Insight> updated = valueCaptor.getAllValues().stream()
                .map(this::readInsight)
                .toList();
        assertThat(updated).anyMatch(insight ->
                insight.text().equals("合并后的角色回答规则") && insight.state() == InsightState.ACTIVE);
        assertThat(updated).anyMatch(insight -> insight.state() == InsightState.STALE);
    }

    @Test
    void lifecycleManagementMarksOldOrLowConfidenceInsightsAsStale() throws Exception {
        Insight old = insight("old", "旧规则", 3, 0.7, InsightState.ACTIVE, NOW.minusSeconds(31L * 24 * 60 * 60));
        Insight low = insight("low", "低置信度规则", 3, 0.1, InsightState.ACTIVE);
        InsightService service = service(prompt -> List.of());
        when(redis.keys("agent:insights:*")).thenReturn(java.util.Set.of("agent:insights:42"));
        when(hashOps.entries("agent:insights:42")).thenReturn(Map.of(
                old.id(), objectMapper.writeValueAsString(old),
                low.id(), objectMapper.writeValueAsString(low)
        ));

        service.lifecycleManagement();

        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOps, org.mockito.Mockito.times(2)).put(eq("agent:insights:42"), anyString(), valueCaptor.capture());
        assertThat(valueCaptor.getAllValues().stream().map(this::readInsight))
                .allMatch(insight -> insight.state() == InsightState.STALE);
    }

    private InsightService service(Function<String, List<String>> generator) {
        return new InsightService(redis, objectMapper, generator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Insight readInsight(String json) {
        try {
            return objectMapper.readValue(json, Insight.class);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Map<Object, Object> toEntries(List<Insight> insights) throws Exception {
        java.util.LinkedHashMap<Object, Object> entries = new java.util.LinkedHashMap<>();
        for (Insight insight : insights) {
            entries.put(insight.id(), objectMapper.writeValueAsString(insight));
        }
        return entries;
    }

    private Insight insight(String id, String text, int useCount, double confidence, InsightState state) {
        return insight(id, text, useCount, confidence, state, NOW);
    }

    private Insight insight(String id, String text, int useCount, double confidence, InsightState state, Instant lastUsedAt) {
        return new Insight(id, text, NOW.minusSeconds(60), lastUsedAt, useCount, confidence, state);
    }

    private List<AgentTurn> longConversation() {
        return List.of(
                AgentTurn.user("角色有哪些？"),
                AgentTurn.assistant("先列主要角色。"),
                AgentTurn.user("声优呢？"),
                AgentTurn.assistant("补充声优信息。"),
                AgentTurn.user("评分呢？"),
                AgentTurn.assistant("补充评分和放送信息。")
        );
    }
}
