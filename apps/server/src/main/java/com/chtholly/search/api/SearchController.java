package com.chtholly.search.api;

import com.chtholly.search.api.dto.SearchResponse;
import com.chtholly.search.api.dto.SuggestResponse;
import com.chtholly.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/** 搜索接口：关键词检索与联想建议。 */
@Tag(name = "搜索", description = "全文搜索、联想建议")
@RestController
@RequestMapping("/api/v1/search")
@Validated
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final JwtService jwtService;

    @Operation(summary = "关键词搜索")
    @GetMapping
    public SearchResponse search(@RequestParam("q") @NotBlank String q,
                                 @RequestParam(value = "size", required = false, defaultValue = "20") @Min(1) int size,
                                 @RequestParam(value = "tags", required = false) String tagsCsv,
                                 @RequestParam(value = "after", required = false) String after,
                                 @AuthenticationPrincipal Jwt jwt) {
        Long userId = (jwt == null) ? null : jwtService.extractUserId(jwt);
        return searchService.search(q, size, tagsCsv, after, userId);
    }

    @Operation(summary = "搜索联想建议")
    @GetMapping("/suggest")
    public SuggestResponse suggest(@RequestParam("prefix") @NotBlank String prefix,
                                   @RequestParam(value = "size", required = false, defaultValue = "10") @Min(1) int size) {
        return searchService.suggest(prefix, size);
    }
}
