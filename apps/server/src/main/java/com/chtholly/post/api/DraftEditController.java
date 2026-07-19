package com.chtholly.post.api;

import com.chtholly.auth.token.JwtService;
import com.chtholly.common.ratelimit.RateLimit;
import com.chtholly.common.ratelimit.RateLimitDimension;
import com.chtholly.post.draftedit.DraftEditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Explicit preview, confirm, and reject endpoints for controlled draft edits. */
@RestController
@RequestMapping("/api/v1/posts/{draftId}/draft-edit/previews")
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DraftEditController {

    private final DraftEditService service;
    private final JwtService jwtService;

    @PostMapping
    @RateLimit(key = "posts:draft-edit:preview", maxRequests = 5, windowSeconds = 60,
            dimension = RateLimitDimension.USER)
    public DraftEditService.PreviewResult create(
            @PathVariable long draftId,
            @Valid @RequestBody CreatePreviewRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.createPreview(
                jwtService.extractUserId(jwt),
                draftId,
                request.baseContent(),
                request.baseContentSha256(),
                request.instruction());
    }

    @PostMapping("/{previewId}/confirm")
    public DraftEditService.DecisionResult confirm(
            @PathVariable long draftId,
            @PathVariable long previewId,
            @Valid @RequestBody DecisionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.confirm(
                jwtService.extractUserId(jwt), draftId, previewId, request.previewHash());
    }

    @PostMapping("/{previewId}/reject")
    public DraftEditService.DecisionResult reject(
            @PathVariable long draftId,
            @PathVariable long previewId,
            @Valid @RequestBody DecisionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return service.reject(
                jwtService.extractUserId(jwt), draftId, previewId, request.previewHash());
    }

    public record CreatePreviewRequest(
            @NotNull @Size(max = 200_000) String baseContent,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String baseContentSha256,
            @NotBlank @Size(max = 2_000) String instruction) {
    }

    public record DecisionRequest(
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String previewHash) {
    }
}
