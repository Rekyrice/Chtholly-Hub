package com.chtholly.agent.cognitive;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperienceServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-04T01:00:00Z");

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ListOperations<String, String> listOps;
    @Mock
    private HashOperations<String, Object, Object> hashOps;
    @Mock
    private com.chtholly.agent.experience.ArchivedExperienceMapper archivedExperienceMapper;

    private ObjectMapper objectMapper;
    private ExperienceService.TextGenerator textGenerator;
    private ExperienceService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        when(redis.opsForList()).thenReturn(listOps);
        textGenerator = org.mockito.Mockito.mock(ExperienceService.TextGenerator.class);
        service = new ExperienceService(
                redis,
                objectMapper,
                archivedExperienceMapper,
                textGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storeExperiencesPushesJsonAndKeepsRecentFifty() throws Exception {
        Observation observation = new Observation("她在文章里写到时间的重量。", 0.82, NOW, "cognitive-cycle");

        service.storeExperiences(List.of(observation));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOps).rightPush(eq("agent:experiences:global"), payloadCaptor.capture());
        com.chtholly.agent.experience.Experience saved = objectMapper.readValue(
                payloadCaptor.getValue(),
                com.chtholly.agent.experience.Experience.class);
        assertThat(saved.text()).isEqualTo(observation.text());
        assertThat(saved.importance()).isEqualTo(8);
        assertThat(saved.source()).isEqualTo(observation.source());
        assertThat(saved.createdAt()).isEqualTo(observation.createdAt());
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

    @Test
    void storeSingleExperiencePushesJsonAndKeepsRecentFifty() throws Exception {
        com.chtholly.agent.experience.Experience experience = new com.chtholly.agent.experience.Experience(
                "有新客人来了。", 4, "user-registered", NOW);

        service.store(experience);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(listOps).rightPush(eq("agent:experiences:global"), payloadCaptor.capture());
        com.chtholly.agent.experience.Experience saved = objectMapper.readValue(
                payloadCaptor.getValue(),
                com.chtholly.agent.experience.Experience.class);
        assertThat(saved).isEqualTo(experience);
        verify(listOps).trim("agent:experiences:global", -50, -1);
    }

    @Test
    void weeklyConsolidationStoresSummaryForCurrentWeek() throws Exception {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(textGenerator.generate(anyString())).thenReturn("这一周有人带来了新故事，我把它们轻轻记下来了。");
        com.chtholly.agent.experience.Experience old = new com.chtholly.agent.experience.Experience(
                "我读完了一篇文章。", 3, "post-published", NOW.minusSeconds(86_400));
        when(listOps.range("agent:experiences:global", 0, -1)).thenReturn(List.of(objectMapper.writeValueAsString(old)));

        service.weeklyConsolidation();

        verify(hashOps).put(
                eq("agent:experiences:weekly"),
                eq("2026-W27"),
                eq("这一周有人带来了新故事，我把它们轻轻记下来了。"));
    }

    @Test
    void monthlyArchivalArchivesOnlyOldMemorableExperiencesAndDeletesRedisList() throws Exception {
        com.chtholly.agent.experience.Experience memorable = new com.chtholly.agent.experience.Experience(
                "我不想忘记这件事。", 8, "post-published", NOW.minusSeconds(31L * 86_400));
        com.chtholly.agent.experience.Experience ordinary = new com.chtholly.agent.experience.Experience(
                "普通的一天。", 3, "community-quiet", NOW.minusSeconds(31L * 86_400));
        com.chtholly.agent.experience.Experience recent = new com.chtholly.agent.experience.Experience(
                "刚发生的事。", 9, "user-registered", NOW.minusSeconds(2L * 86_400));
        when(listOps.range("agent:experiences:global", 0, -1)).thenReturn(List.of(
                objectMapper.writeValueAsString(memorable),
                objectMapper.writeValueAsString(ordinary),
                objectMapper.writeValueAsString(recent)
        ));

        service.monthlyArchival();

        verify(archivedExperienceMapper).archive(memorable);
        verify(archivedExperienceMapper, never()).archive(ordinary);
        verify(archivedExperienceMapper, never()).archive(recent);
        verify(redis).delete("agent:experiences:global");
    }
}
