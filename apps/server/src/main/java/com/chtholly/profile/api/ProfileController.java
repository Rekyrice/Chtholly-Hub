package com.chtholly.profile.api;

import com.chtholly.profile.api.dto.ProfilePatchRequest;
import com.chtholly.profile.api.dto.ProfileResponse;
import com.chtholly.storage.OssStorageService;
import com.chtholly.auth.token.JwtService;
import com.chtholly.profile.service.ProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * REST API for the authenticated user's profile updates and avatar upload.
 */
@RestController
@RequestMapping("/api/v1/profile")
@Validated
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final JwtService jwtService;
    private final OssStorageService ossStorageService;

    /**
     * Partially updates the caller's profile fields.
     *
     * @param jwt authenticated user JWT
     * @param request fields to patch
     * @return updated profile snapshot
     */
    @PatchMapping
    public ProfileResponse patch(@AuthenticationPrincipal Jwt jwt,
                                 @Valid @RequestBody ProfilePatchRequest request) {
        long userId = jwtService.extractUserId(jwt);

        return profileService.updateProfile(userId, request);
    }

    /**
     * Uploads an avatar image to OSS and persists the new avatar URL on the profile.
     *
     * @param jwt authenticated user JWT
     * @param file avatar multipart file
     * @return updated profile snapshot including the new avatar URL
     */
    @PostMapping("/avatar")
    public ProfileResponse uploadAvatar(@AuthenticationPrincipal Jwt jwt,
                                        @RequestPart("file") MultipartFile file) {
        long userId = jwtService.extractUserId(jwt);
        String url = ossStorageService.uploadAvatar(userId, file);

        return profileService.updateAvatar(userId, url);
    }
}
