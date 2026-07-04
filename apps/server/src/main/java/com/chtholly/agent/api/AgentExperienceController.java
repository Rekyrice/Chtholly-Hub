package com.chtholly.agent.api;

import com.chtholly.agent.api.dto.AgentExperienceResponse;
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
                        experience.createdAt(),
                        experience.source()))
                .toList();
    }
}
