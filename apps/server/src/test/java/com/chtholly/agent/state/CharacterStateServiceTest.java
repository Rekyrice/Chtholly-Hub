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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;
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
        stubHashOps();
        when(hashOps.entries("agent:character-state:9")).thenReturn(Map.ofEntries(
                entry("personality.warmth", "0.7"),
                entry("personality.curiosity", "0.8"),
                entry("personality.playfulness", "0.5"),
                entry("mood.valence", "0.0"),
                entry("mood.arousal", "0.5"),
                entry("mood.baseline", "0.0"),
                entry("relationship.intimacy", "0.0"),
                entry("relationship.interactionCount", "1"),
                entry("relationship.lastSeen", "2026-07-03T00:00:00Z"),
                entry("needs.social", "0.0"),
                entry("needs.creative", "0.0"),
                entry("needs.knowledge", "0.0"),
                entry("behaviorProb.proactiveGreet", "0.5"),
                entry("behaviorProb.shareObservation", "0.3"),
                entry("behaviorProb.recommendPost", "0.3")
        ));

        service.recordInteraction(9L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> entriesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(eq("agent:character-state:9"), entriesCaptor.capture());
        assertThat(entriesCaptor.getValue().get("relationship.interactionCount")).isEqualTo("2");
        assertThat(Double.parseDouble(entriesCaptor.getValue().get("relationship.intimacy")))
                .isEqualTo(0.1 * Math.log(3));
        verify(redis).expire("agent:character-state:9", Duration.ofDays(30));
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

    private static Clock fixedClockAtHour(int hour) {
        return Clock.fixed(
                Instant.parse("2026-07-03T%02d:00:00Z".formatted(hour)),
                ZoneOffset.UTC);
    }

    private void stubHashOps() {
        when(redis.opsForHash()).thenReturn(hashOps);
    }
}
