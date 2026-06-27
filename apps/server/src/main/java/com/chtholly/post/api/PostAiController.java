package com.chtholly.post.api;

import com.chtholly.post.api.dto.DescriptionSuggestRequest;
import com.chtholly.post.api.dto.DescriptionSuggestResponse;
import com.chtholly.llm.service.PostDescriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * LLM-assisted post authoring helpers (available when {@code llm.enabled=true}).
 */
@RestController
@RequestMapping(path = "/api/v1/posts", produces = MediaType.APPLICATION_JSON_VALUE)
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PostAiController {

    private final PostDescriptionService descriptionService;

    /**
     * Generates a short post description (max ~50 characters) from draft content.
     *
     * @param req request body containing markdown or plain post content
     * @return suggested description text
     */
    @PostMapping(path = "/description/suggest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DescriptionSuggestResponse suggest(@Valid @RequestBody DescriptionSuggestRequest req) {
        String desc = descriptionService.generateDescription(req.content());
        return new DescriptionSuggestResponse(desc);
    }
}