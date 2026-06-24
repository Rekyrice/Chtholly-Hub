package com.chtholly.profile.service;

import com.chtholly.profile.api.dto.ProfilePatchRequest;
import com.chtholly.profile.api.dto.ProfileResponse;
import com.chtholly.user.domain.User;

import java.util.Optional;

/**
 * 个人资料业务接口。
 */
public interface ProfileService {

    Optional<User> getById(long userId);

    ProfileResponse updateProfile(long userId, ProfilePatchRequest req);

    ProfileResponse updateAvatar(long userId, String avatarUrl);
}