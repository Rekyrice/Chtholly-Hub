package com.chtholly.agent.api;

import com.chtholly.agent.config.AgentExtensionComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.chtholly.agent.api.dto.AgentExperienceResponse;
import com.chtholly.agent.api.dto.AgentExperienceTimelineResponse;
import com.chtholly.agent.api.dto.AgentArchivedExperienceResponse;
import com.chtholly.agent.api.dto.AgentWeeklyExperienceResponse;
import com.chtholly.agent.cognitive.ExperienceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public read API for Chtholly's recent cognitive experiences.
 */
@RestController
@AgentExtensionComponent
@ConditionalOnProperty(prefix = "agent.extensions.experience", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping(path = "/api/v1/agent/experiences", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class AgentExperienceController {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final ExperienceService experienceService;

    /**
     * Returns recent observations newest first.
     *
     * @param limit requested item count
     * @return recent experience items
     */
    @GetMapping
    public List<AgentExperienceResponse> recent(
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int safeLimit = Math.clamp(limit, 1, MAX_LIMIT);
        return experienceService.getRecentExperiences(safeLimit).stream()
                .map(experience -> new AgentExperienceResponse(
                        experience.text(),
                        experience.valueScore(),
                        Math.clamp((int) Math.round(experience.valueScore() * 10), 1, 10),
                        experience.createdAt(),
                        experience.source()))
                .toList();
    }

    /**
     * Returns all room timeline layers: recent items, weekly summaries, and archived memories.
     *
     * @return room timeline data
     */
    @GetMapping("/timeline")
    public AgentExperienceTimelineResponse timeline() {
        List<AgentExperienceResponse> recent = experienceService.getRecentExperienceItems(5).stream()
                .map(experience -> new AgentExperienceResponse(
                        experience.text(),
                        experience.importance() / 10.0,
                        experience.importance(),
                        experience.createdAt(),
                        experience.source()))
                .toList();
        List<AgentWeeklyExperienceResponse> weekly = experienceService.getWeeklySummaries(4).stream()
                .map(summary -> new AgentWeeklyExperienceResponse(summary.weekKey(), summary.summary()))
                .toList();
        List<AgentArchivedExperienceResponse> archived = experienceService.getArchivedMemories(5).stream()
                .map(memory -> new AgentArchivedExperienceResponse(
                        memory.id(),
                        memory.text(),
                        memory.importance(),
                        memory.source(),
                        memory.createdAt(),
                        memory.archivedAt()))
                .toList();
        return new AgentExperienceTimelineResponse(recent, weekly, archived);
    }
}
