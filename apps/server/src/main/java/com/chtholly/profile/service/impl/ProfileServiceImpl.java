package com.chtholly.profile.service.impl;

import com.chtholly.profile.api.dto.ProfilePatchRequest;
import com.chtholly.profile.api.dto.ProfileResponse;
import com.chtholly.profile.event.AuthorProfileChangedPayload;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Objects;

import com.chtholly.post.id.SnowflakeIdGenerator;
import com.chtholly.relation.outbox.OutboxMapper;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.chtholly.profile.service.ProfileService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Default implementation of {@link ProfileService}.
 * Reads and updates authenticated user profile fields and avatar URL.
 */
@Service
@RequiredArgsConstructor
public class ProfileServiceImpl implements ProfileService {

    private final UserMapper userMapper;
    private final OutboxMapper outboxMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final ObjectMapper objectMapper;

    /**
     * Loads a user entity by ID for internal profile operations.
     *
     * @param userId user ID
     * @return matching user, or empty if not found
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<User> getById(long userId) {
        return Optional.ofNullable(userMapper.findById(userId));
    }

    /**
     * Partially updates profile fields for the authenticated user.
     *
     * @param userId current user ID
     * @param req patch request with optional fields to update
     * @return updated profile snapshot
     * @throws BusinessException if the user is missing, no fields were provided, or handle is taken
     */
    @Override
    @Transactional
    public ProfileResponse updateProfile(long userId, ProfilePatchRequest req) {
        // 读取当前用户，作为更新与唯一性校验的基准
        User current = userMapper.findById(userId);

        if (current == null) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在");
        }

        // 至少要提交一个字段，否则属于无效请求
        boolean hasAnyField = req.nickname() != null || req.bio() != null || req.gender() != null
                || req.birthday() != null || req.handle() != null || req.school() != null
                || req.tagJson() != null;

        if (!hasAnyField) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "未提交任何更新字段");
        }

        // 用户标识唯一性校验：仅在提交且非空时检查（排除自己）
        if (req.handle() != null && !req.handle().isBlank()) {
            boolean exists = userMapper.existsByHandleExceptId(req.handle(), current.getId());

            if (exists) {
                throw new BusinessException(ErrorCode.HANDLE_EXISTS);
            }
        }

        // 仅写入非空字段，避免把未提交字段覆盖成 null
        User patch = getUser(req, current);
        userMapper.updateProfile(patch);

        // 更新后回读，保证返回数据为最新快照
        User updated = userMapper.findById(userId);
        writeAuthorProfileChangedIfNeeded(current, updated);

        return toResponse(updated);
    }

    private static User getUser(ProfilePatchRequest req, User current) {
        User patch = new User();
        patch.setId(current.getId());
        if (req.nickname() != null) {
            patch.setNickname(req.nickname().trim());
        }
        if (req.bio() != null) {
            patch.setBio(req.bio().trim());
        }
        if (req.gender() != null) {
            patch.setGender(req.gender().trim().toUpperCase());
        }
        if (req.birthday() != null) {
            patch.setBirthday(req.birthday());
        }
        if (req.handle() != null) {
            patch.setHandle(req.handle().trim());
        }
        if (req.school() != null) {
            patch.setSchool(req.school().trim());
        }
        if (req.tagJson() != null) {
            patch.setTagsJson(req.tagJson());
        }
        return patch;
    }

    /**
     * Updates the avatar URL after upload completes elsewhere.
     *
     * @param userId current user ID
     * @param avatarUrl new avatar URL from object storage
     * @return updated profile snapshot
     * @throws BusinessException if the user does not exist
     */
    @Override
    @Transactional
    public ProfileResponse updateAvatar(long userId, String avatarUrl) {
        User current = userMapper.findById(userId);
        if (current == null) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在");
        }

        // 仅更新头像字段
        User patch = new User();
        patch.setId(userId);
        patch.setAvatar(avatarUrl);
        userMapper.updateProfile(patch);

        // 更新后回读，保证返回最新头像地址
        User updated = userMapper.findById(userId);
        writeAuthorProfileChangedIfNeeded(current, updated);
        return toResponse(updated);
    }

    private void writeAuthorProfileChangedIfNeeded(User before, User after) {
        if (after == null || !publicProfileChanged(before, after)) {
            return;
        }
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(AuthorProfileChangedPayload.of(after.getId()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize author profile event for user " + after.getId(), e);
        }
        outboxMapper.insert(
                idGenerator.nextId(),
                "user",
                after.getId(),
                "AUTHOR_PROFILE_CHANGED",
                payload
        );
    }

    private boolean publicProfileChanged(User before, User after) {
        return !Objects.equals(before.getNickname(), after.getNickname())
                || !Objects.equals(before.getAvatar(), after.getAvatar())
                || !Objects.equals(before.getBio(), after.getBio())
                || !Objects.equals(before.getHandle(), after.getHandle())
                || !Objects.equals(before.getTagsJson(), after.getTagsJson());
    }

    private ProfileResponse toResponse(User user) {
        return new ProfileResponse(
                user.getId(),
                user.getNickname(),
                user.getAvatar(),
                user.getBio(),
                user.getHandle(),
                user.getGender(),
                user.getBirthday(),
                user.getSchool(),
                user.getPhone(),
                user.getEmail(),
                user.getTagsJson()
        );
    }
}
