package com.chtholly.tag.api;

import com.chtholly.common.web.HttpCacheHelper;
import com.chtholly.tag.api.dto.TagResponse;
import com.chtholly.tag.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public tag listing API; usage counts are maintained by the post publish flow.
 */
@Tag(name = "标签", description = "标签管理")
@RestController
@RequestMapping("/api/v1/tags")
@Validated
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    /**
     * Lists tags ordered by reference count descending.
     *
     * @param limit maximum number of tags to return
     * @return tag list
     */
    @Operation(summary = "标签列表（按引用次数降序）")
    @GetMapping
    public ResponseEntity<List<TagResponse>> list(@RequestParam(value = "limit", defaultValue = "50") int limit,
                                                  @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        String etag = tagService.computeListEtag(limit);
        if (HttpCacheHelper.matchesIfNoneMatch(ifNoneMatch, etag)) {
            return HttpCacheHelper.notModifiedPublic(etag);
        }
        List<TagResponse> body = tagService.listTags(limit);
        return HttpCacheHelper.okPublic(body, etag);
    }
}
