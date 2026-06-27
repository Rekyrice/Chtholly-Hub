package com.chtholly.user.api;

import com.chtholly.common.web.HttpCacheHelper;
import com.chtholly.user.api.dto.PublicUserResponse;
import com.chtholly.user.service.UserPublicService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public user profile API for profile pages and author discovery.
 */
@Tag(name = "用户", description = "用户公开信息")
@RestController
@RequestMapping("/api/v1/users")
@Validated
@RequiredArgsConstructor
public class UserController {

    private final UserPublicService userPublicService;

    /**
     * Returns public profile information for a user handle.
     *
     * @param handle unique user handle
     * @return public user profile
     */
    @Operation(summary = "公开用户资料")
    @GetMapping("/{handle}")
    public ResponseEntity<PublicUserResponse> profile(@PathVariable("handle") String handle,
                                                      @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        String etag = userPublicService.computeEtagByHandle(handle);
        if (HttpCacheHelper.matchesIfNoneMatch(ifNoneMatch, etag)) {
            return HttpCacheHelper.notModifiedPublic(etag);
        }
        PublicUserResponse body = userPublicService.getByHandle(handle);
        return HttpCacheHelper.okPublic(body, etag);
    }
}
