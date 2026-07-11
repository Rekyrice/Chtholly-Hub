package com.chtholly.agent.mood;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.chtholly.agent.state.CharacterStateService;
import com.chtholly.post.service.PostService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Updates Chtholly's slow-changing mood from time, community, and relationships.
 */
@Service
@ConditionalOnProperty(prefix = "agent.extensions.mood", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MoodEngine {

    private final CharacterStateService characterState;
    private final PostService postService;
    private final InteractionService interactionService;

    public MoodEngine(CharacterStateService characterState,
                      PostService postService,
                      InteractionService interactionService) {
        this.characterState = characterState;
        this.postService = postService;
        this.interactionService = interactionService;
    }

    /**
     * Updates mood every hour based on three factors.
     */
    @Scheduled(fixedRate = 3_600_000L, initialDelay = 60_000L)
    public void updateMood() {
        double timeValence = characterState.getMoodBaseline();
        double communityFactor = calculateCommunityActivity();
        double relationshipFactor = calculateRelationshipDepth();

        double newMood = 0.4 * timeValence
                + 0.3 * communityFactor
                + 0.3 * relationshipFactor;

        double currentMood = characterState.getMoodValence();
        double regressed = currentMood * 0.7 + newMood * 0.3;

        characterState.updateMoodValence(clamp(regressed, -1.0, 1.0));
    }

    /**
     * Calculates community activity in the last 24 hours.
     *
     * @return activity factor from -1.0 to 1.0.
     */
    double calculateCommunityActivity() {
        long recentPosts = postService.countSince(Duration.ofHours(24));
        long recentInteractions = interactionService.countSince(Duration.ofHours(24));

        double postScore = Math.min(recentPosts / 5.0, 1.0) * 2 - 1;
        double interactionScore = Math.min(recentInteractions / 20.0, 1.0) * 2 - 1;

        return clamp((postScore + interactionScore) / 2.0, -1.0, 1.0);
    }

    /**
     * Calculates relationship depth across active users.
     *
     * @return relationship factor from -1.0 to 1.0.
     */
    double calculateRelationshipDepth() {
        List<Double> intimacies = characterState.getActiveUserIntimacies();
        if (intimacies == null || intimacies.isEmpty()) {
            return -0.3;
        }

        double avg = intimacies.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

        return clamp(avg * 2 - 1, -1.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
