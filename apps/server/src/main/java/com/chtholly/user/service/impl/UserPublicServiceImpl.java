package com.chtholly.user.service.impl;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.web.HttpCacheHelper;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.post.mapper.PostMapper;
import com.chtholly.user.api.dto.PublicUserResponse;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.chtholly.user.service.UserPublicService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Resolves public user profile data by handle for profile pages.
 */
@Service
@RequiredArgsConstructor
public class UserPublicServiceImpl implements UserPublicService {

    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final ObjectMapper objectMapper;

    /**
     * Loads a public profile snapshot including published post count.
     *
     * @param handle user handle (trimmed)
     * @return public profile response
     * @throws BusinessException if the handle is blank or the user does not exist
     */
    @Override
    public PublicUserResponse getByHandle(String handle) {
        if (handle == null || handle.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户标识不能为空");
        }
        User user = userMapper.findByHandle(handle.trim());
        if (user == null) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在");
        }
        long postCount = postMapper.countPublicPublishedByCreator(user.getId());
        return new PublicUserResponse(
                String.valueOf(user.getId()),
                user.getHandle(),
                user.getNickname(),
                user.getAvatar(),
                user.getBio(),
                parseTags(user.getTagsJson()),
                user.getCreatedAt(),
                postCount
        );
    }

    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> tags = objectMapper.readValue(tagsJson, new TypeReference<>() {});
            if (tags == null) {
                return List.of();
            }
            return tags.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .filter(tag -> !tag.isEmpty())
                    .toList();
        } catch (JsonProcessingException | ClassCastException ignored) {
            return List.of();
        }
    }

    @Override
    public String computeEtagByHandle(String handle) {
        if (handle == null || handle.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户标识不能为空");
        }
        User user = userMapper.findByHandle(handle.trim());
        if (user == null) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND, "用户不存在");
        }
        Instant updatedAt = user.getUpdatedAt();
        return HttpCacheHelper.hashEtag(updatedAt != null ? updatedAt.toString() : "");
    }
}
