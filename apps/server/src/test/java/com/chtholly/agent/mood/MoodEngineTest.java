package com.chtholly.agent.mood;

import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.post.service.PostService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MoodEngineTest {

    @Test
    void updateMoodCombinesTimeCommunityAndRelationshipThenRegressesCurrentMood() {
        CharacterStateService characterStateService = mock(CharacterStateService.class);
        PostService postService = mock(PostService.class);
        InteractionService interactionService = mock(InteractionService.class);
        MoodEngine engine = new MoodEngine(characterStateService, postService, interactionService);

        when(characterStateService.getMoodBaseline()).thenReturn(0.2);
        when(characterStateService.getMoodValence()).thenReturn(0.4);
        when(characterStateService.getActiveUserIntimacies()).thenReturn(List.of(0.5, 0.7));
        when(postService.countSince(Duration.ofHours(24))).thenReturn(5L);
        when(interactionService.countSince(Duration.ofHours(24))).thenReturn(20L);

        engine.updateMood();

        verify(characterStateService).updateMoodValence(
                org.mockito.ArgumentMatchers.doubleThat(value -> Math.abs(value - 0.412) < 0.000001));
    }

    @Test
    void updateMoodTreatsEmptyRelationshipsAsSlightlyLonelyAndClampsResult() {
        CharacterStateService characterStateService = mock(CharacterStateService.class);
        PostService postService = mock(PostService.class);
        InteractionService interactionService = mock(InteractionService.class);
        MoodEngine engine = new MoodEngine(characterStateService, postService, interactionService);

        when(characterStateService.getMoodBaseline()).thenReturn(-0.3);
        when(characterStateService.getMoodValence()).thenReturn(-2.0);
        when(characterStateService.getActiveUserIntimacies()).thenReturn(List.of());
        when(postService.countSince(Duration.ofHours(24))).thenReturn(0L);
        when(interactionService.countSince(Duration.ofHours(24))).thenReturn(0L);

        engine.updateMood();

        verify(characterStateService).updateMoodValence(-1.0);
    }
}
