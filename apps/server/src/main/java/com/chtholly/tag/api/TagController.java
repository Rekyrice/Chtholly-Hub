package com.chtholly.tag.api;

import com.chtholly.tag.api.dto.TagResponse;
import com.chtholly.tag.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 标签 API：公开列表；计数由发帖流程维护。 */
@Tag(name = "标签", description = "标签管理")
@RestController
@RequestMapping("/api/v1/tags")
@Validated
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @Operation(summary = "标签列表（按引用次数降序）")
    @GetMapping
    public List<TagResponse> list(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return tagService.listTags(limit);
    }
}
