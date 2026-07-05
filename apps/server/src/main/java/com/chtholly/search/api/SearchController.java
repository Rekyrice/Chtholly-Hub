package com.chtholly.search.api;

import com.chtholly.common.api.pagination.PageResponse;
import com.chtholly.common.api.pagination.Pagination;
import com.chtholly.post.api.dto.FeedItemResponse;
import com.chtholly.search.api.dto.HubFeedResponse;
import com.chtholly.search.api.dto.SuggestResponse;
import com.chtholly.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.chtholly.auth.token.JwtService;

/**
 * Full-text search and autocomplete suggestion endpoints backed by Elasticsearch.
 */
@Tag(name = "搜索", description = "全文搜索、联想建议")
@RestController
@RequestMapping("/api/v1/search")
@Validated
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final JwtService jwtService;

    /**
     * Searches posts by keyword with optional tag filter and cursor pagination.
     *
     * @param q search query text
     * @param size maximum hits to return
     * @param tagsCsv optional comma-separated tag slugs
     * @param after optional cursor for next page
     * @param jwt optional JWT for personalized fields (may be null)
     * @return search result page
     */
    @Operation(summary = "关键词搜索")
    @GetMapping
    public PageResponse<FeedItemResponse> search(@RequestParam("q") @NotBlank String q,
                                 @RequestParam(value = "size", required = false, defaultValue = "20") @Min(1) @Max(50) int size,
                                 @RequestParam(value = "tags", required = false) String tagsCsv,
                                 @RequestParam(value = "after", required = false) String after,
                                 @RequestParam(value = "cursor", required = false) String cursor,
                                 @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        String pageCursor = cursor != null && !cursor.isBlank() ? cursor : after;
        return searchService.search(q, size, tagsCsv, pageCursor, userId);
    }

    /**
     * Returns Hub page regions from one Elasticsearch msearch request.
     *
     * @param interestTags optional comma-separated tags for recommendations
     * @param jwt optional JWT for personalized fields
     * @return aggregated Hub feed sections with per-section status
     */
    @Operation(summary = "Hub 聚合搜索 Feed")
    @GetMapping("/hub-feed")
    public HubFeedResponse hubFeed(@RequestParam(value = "interestTags", required = false) String interestTags,
                                   @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        return searchService.hubFeed(interestTags, userId);
    }

    /**
     * Returns prefix-based search suggestions for autocomplete.
     *
     * @param prefix typed prefix text
     * @param size maximum suggestions to return
     * @return suggestion list
     */
    @Operation(summary = "搜索联想建议")
    @GetMapping("/suggest")
    public SuggestResponse suggest(@RequestParam("prefix") @NotBlank String prefix,
                                   @RequestParam(value = "size", required = false, defaultValue = "10") @Min(1) int size) {
        return searchService.suggest(prefix, size);
    }
}
