package com.chtholly.agent.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CharacterStateServiceTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private CharacterStateService service;

    @BeforeEach
    void setUp() {
        service = new CharacterStateService(redis, new ObjectMapper());
    }

    @Test
    void loadCreatesDefaultHashWithSlidingTtlForNewUser() {
        stubHashOps();
        when(hashOps.entries("agent:character-state:42")).thenReturn(Map.of());

        CharacterState state = service.load(42L);

        assertThat(state.personality().warmth()).isEqualTo(0.7);
        assertThat(state.personality().curiosity()).isEqualTo(0.8);
        assertThat(state.personality().playfulness()).isEqualTo(0.5);
        assertThat(state.mood().valence()).isEqualTo(0.0);
        assertThat(state.mood().arousal()).isEqualTo(0.5);
        assertThat(state.relationship().interactionCount()).isZero();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> entriesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(eq("agent:character-state:42"), entriesCaptor.capture());
        assertThat(entriesCaptor.getValue())
                .containsEntry("personality.warmth", "0.7")
                .containsEntry("relationship.interactionCount", "0")
                .containsKey("relationship.lastSeen");
        verify(redis).expire("agent:character-state:42", Duration.ofDays(30));
    }

    @Test
    void loadDeserializesExistingHashAndRefreshesTtl() {
        stubHashOps();
        when(hashOps.entries("agent:character-state:7")).thenReturn(Map.ofEntries(
                entry("personality.warmth", "0.6"),
                entry("personality.curiosity", "0.9"),
                entry("personality.playfulness", "0.4"),
                entry("mood.valence", "-0.2"),
                entry("mood.arousal", "0.3"),
                entry("mood.baseline", "0.1"),
                entry("relationship.intimacy", "0.25"),
                entry("relationship.interactionCount", "3"),
                entry("relationship.lastSeen", "2026-07-03T00:00:00Z"),
                entry("needs.social", "0.2"),
                entry("needs.creative", "0.3"),
                entry("needs.knowledge", "0.4"),
                entry("behaviorProb.proactiveGreet", "0.1"),
                entry("behaviorProb.shareObservation", "0.2"),
                entry("behaviorProb.recommendPost", "0.3")
        ));

        CharacterState state = service.load(7L);

        assertThat(state.mood().valence()).isEqualTo(-0.2);
        assertThat(state.relationship().intimacy()).isEqualTo(0.25);
        assertThat(state.relationship().interactionCount()).isEqualTo(3);
        assertThat(state.relationship().lastSeen()).isEqualTo(Instant.parse("2026-07-03T00:00:00Z"));
        verify(redis).expire("agent:character-state:7", Duration.ofDays(30));
    }

    @Test
    void recordInteractionIncrementsCountAndGrowsIntimacyLogarithmically() {
        org.mockito.Mockito.doReturn(2L).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        service.recordInteraction(9L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DefaultRedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(scriptCaptor.capture(), keysCaptor.capture(), any(Object[].class));
        assertThat(scriptCaptor.getValue().getScriptAsString())
                .contains("HINCRBY", "relationship.intimacy", "PEXPIRE");
        assertThat(keysCaptor.getValue()).containsExactly("agent:character-state:9");
    }

    @ParameterizedTest
    @CsvSource({
            "6,0.2",
            "10,0.1",
            "15,0.0",
            "19,0.1",
            "22,-0.1",
            "0,-0.1",
            "2,-0.3"
    })
    void getMoodBaselineReturnsTimeAwareBaseline(int hour, double expectedBaseline) {
        CharacterStateService clockedService = new CharacterStateService(
                redis,
                new ObjectMapper(),
                fixedClockAtHour(hour));

        assertThat(clockedService.getMoodBaseline()).isEqualTo(expectedBaseline);
    }

    @Test
    void updateAndGetMoodValenceUsesGlobalMoodHash() {
        stubHashOps();
        service.updateMoodValence(1.8);
        verify(hashOps).put("character:state:mood", "valence", "1.0");

        when(hashOps.get("character:state:mood", "valence")).thenReturn("-0.35");
        assertThat(service.getMoodValence()).isEqualTo(-0.35);
    }

    @Test
    void getActiveUserIntimaciesReadsRelationshipScoresFromStateHashes() {
        stubHashOps();
        when(redis.keys("agent:character-state:*")).thenReturn(Set.of(
                "agent:character-state:1",
                "agent:character-state:2",
                "agent:character-state:3"));
        when(hashOps.get("agent:character-state:1", "relationship.intimacy")).thenReturn("0.25");
        when(hashOps.get("agent:character-state:2", "relationship.intimacy")).thenReturn("bad");
        when(hashOps.get("agent:character-state:3", "relationship.intimacy")).thenReturn("0.75");

        List<Double> intimacies = service.getActiveUserIntimacies();

        assertThat(intimacies).containsExactlyInAnyOrder(0.25, 0.75);
    }

    @Test
    void updateEmotionStoresCurrentEmotionWithThirtyMinuteTtl() {
        CharacterStateService clockedService = new CharacterStateService(
                redis,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-05T03:00:00Z"), ZoneOffset.UTC));
        org.mockito.Mockito.doReturn(1L).when(redis)
                .execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));

        clockedService.updateEmotion(7L, "这个角色为什么会这样？我有点好奇");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DefaultRedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(DefaultRedisScript.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(scriptCaptor.capture(), eq(List.of("character:state:emotion")), argsCaptor.capture());
        assertThat(scriptCaptor.getValue().getScriptAsString())
                .contains("triggeredAtEpochMs", "PEXPIRE");
        assertThat(argsCaptor.getValue()[0]).isEqualTo("1783220400000");
        assertThat(argsCaptor.getValue()[1]).isEqualTo("好奇");
        assertThat(Double.parseDouble(argsCaptor.getValue()[2].toString())).isBetween(0.4, 1.0);
    }

    @Test
    void getCurrentEmotionAppliesThirtyMinuteDecay() {
        CharacterStateService clockedService = new CharacterStateService(
                redis,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-05T03:15:00Z"), ZoneOffset.UTC));
        stubHashOps();
        when(hashOps.entries("character:state:emotion")).thenReturn(Map.of(
                "label", "开心",
                "intensity", "0.8",
                "triggeredAt", "2026-07-05T03:00:00Z",
                "trigger", "user-interaction"
        ));

        EmotionState emotion = clockedService.getCurrentEmotion();

        assertThat(emotion.label()).isEqualTo("开心");
        assertThat(emotion.intensity()).isEqualTo(0.4);
        assertThat(emotion.triggeredAt()).isEqualTo(Instant.parse("2026-07-05T03:00:00Z"));
    }

    @Test
    void getCurrentEmotionFallsBackToCalmWhenDecayedAway() {
        CharacterStateService clockedService = new CharacterStateService(
                redis,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-05T03:31:00Z"), ZoneOffset.UTC));
        stubHashOps();
        when(hashOps.entries("character:state:emotion")).thenReturn(Map.of(
                "label", "感伤",
                "intensity", "0.8",
                "triggeredAt", "2026-07-05T03:00:00Z",
                "trigger", "user-interaction"
        ));

        EmotionState emotion = clockedService.getCurrentEmotion();

        assertThat(emotion.label()).isEqualTo("平静");
        assertThat(emotion.intensity()).isEqualTo(0.2);
        assertThat(emotion.trigger()).isEqualTo("default");
    }

    private static Clock fixedClockAtHour(int hour) {
        return Clock.fixed(
                Instant.parse("2026-07-03T%02d:00:00Z".formatted(hour)),
                ZoneOffset.UTC);
    }

    private void stubHashOps() {
        when(redis.opsForHash()).thenReturn(hashOps);
    }
}
