package com.chtholly.user.service;

import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import com.chtholly.user.model.PublicAuthorSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从 MySQL 权威用户资料中读取公开作者快照。
 */
@Service
@RequiredArgsConstructor
public class PublicAuthorQueryService {

    private final UserMapper userMapper;

    @Transactional(readOnly = true)
    public Optional<PublicAuthorSnapshot> findById(long userId) {
        return Optional.ofNullable(userMapper.findPublicById(userId))
                .map(PublicAuthorQueryService::toSnapshot);
    }

    @Transactional(readOnly = true)
    public Map<Long, PublicAuthorSnapshot> findByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = new LinkedHashSet<>(userIds).stream()
                .filter(java.util.Objects::nonNull)
                .toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, PublicAuthorSnapshot> snapshots = new LinkedHashMap<>();
        for (User user : userMapper.listPublicByIds(distinctIds)) {
            if (user != null && user.getId() != null) {
                snapshots.put(user.getId(), toSnapshot(user));
            }
        }
        return Map.copyOf(snapshots);
    }

    private static PublicAuthorSnapshot toSnapshot(User user) {
        return new PublicAuthorSnapshot(
                user.getId(),
                user.getHandle(),
                user.getNickname(),
                user.getAvatar(),
                user.getBio(),
                user.getTagsJson(),
                user.getCreatedAt()
        );
    }
}
