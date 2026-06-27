package com.chtholly.admin.service;

import com.chtholly.admin.api.dto.AdminUserPageResponse;
import com.chtholly.admin.api.dto.AdminUserResponse;
import com.chtholly.admin.audit.AdminAction;
import com.chtholly.admin.role.Role;
import com.chtholly.auth.token.RefreshTokenStore;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.common.exception.ResourceNotFoundException;
import com.chtholly.config.SiteProperties;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserMapper userMapper;
    private final AdminAuditService auditService;
    private final SiteProperties siteProperties;
    private final RefreshTokenStore refreshTokenStore;

    public AdminUserPageResponse listUsers(String keyword, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 1);
        int offset = (safePage - 1) * safeSize;
        String kw = keyword == null || keyword.isBlank() ? null : keyword.trim();

        List<User> users = userMapper.searchUsers(kw, safeSize + 1, offset);
        boolean hasMore = users.size() > safeSize;
        if (hasMore) {
            users = users.subList(0, safeSize);
        }
        List<AdminUserResponse> items = users.stream().map(AdminUserResponse::from).toList();
        long total = userMapper.countSearchUsers(kw);
        return new AdminUserPageResponse(items, safePage, safeSize, hasMore, total);
    }

    @Transactional
    public void updateRole(long adminUserId, long targetUserId, Role newRole) {
        User target = requireUser(targetUserId);
        if (targetUserId == siteProperties.ownerUserId() && newRole != Role.ADMIN) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能变更站长角色");
        }
        userMapper.updateRole(targetUserId, newRole.name());
        auditService.record(adminUserId, AdminAction.UPDATE_USER_ROLE, "USER", targetUserId,
                Map.of("from", Role.fromString(target.getRole()).name(), "to", newRole.name()));
    }

    @Transactional
    public void banUser(long adminUserId, long targetUserId) {
        if (targetUserId == siteProperties.ownerUserId()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能封禁站长");
        }
        if (targetUserId == adminUserId) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能封禁自己");
        }
        requireUser(targetUserId);
        userMapper.updateBannedAt(targetUserId, Instant.now());
        refreshTokenStore.revokeAll(targetUserId);
        auditService.record(adminUserId, AdminAction.BAN_USER, "USER", targetUserId, Map.of());
    }

    @Transactional
    public void unbanUser(long adminUserId, long targetUserId) {
        requireUser(targetUserId);
        userMapper.updateBannedAt(targetUserId, null);
        auditService.record(adminUserId, AdminAction.UNBAN_USER, "USER", targetUserId, Map.of());
    }

    private User requireUser(long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("用户不存在");
        }
        return user;
    }
}
