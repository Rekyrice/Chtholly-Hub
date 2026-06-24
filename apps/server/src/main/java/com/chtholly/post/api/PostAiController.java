package com.chtholly.post.api;

import com.chtholly.post.api.dto.DescriptionSuggestRequest;
import com.chtholly.post.api.dto.DescriptionSuggestResponse;
import com.chtholly.llm.service.PostDescriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/v1/posts", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class PostAiController {

    private final PostDescriptionService descriptionService;

    /**
     * 生成不超过 50 字的帖子描述。
     * 需要鉴权（默认策略），防止匿名滥用。
     */
    @PostMapping(path = "/description/suggest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DescriptionSuggestResponse suggest(@Valid @RequestBody DescriptionSuggestRequest req) {
        String desc = descriptionService.generateDescription(req.content());
        return new DescriptionSuggestResponse(desc);
    }
}