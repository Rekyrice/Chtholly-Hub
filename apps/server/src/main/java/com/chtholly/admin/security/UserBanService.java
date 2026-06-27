package com.chtholly.admin.security;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.user.domain.User;
import com.chtholly.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 封禁用户校验：登录、刷新令牌与 API 请求统一入口。
 */
@Service
@RequiredArgsConstructor
public class UserBanService {

    private final UserMapper userMapper;

    public boolean isBanned(long userId) {
        User user = userMapper.findById(userId);
        return user != null && user.getBannedAt() != null;
    }

    public void assertNotBanned(User user) {
        if (user != null && user.getBannedAt() != null) {
            throw bannedException();
        }
    }

    public void assertNotBanned(long userId) {
        User user = userMapper.findById(userId);
        assertNotBanned(user);
    }

    public BusinessException bannedException() {
        return new BusinessException(ErrorCode.USER_BANNED, ErrorCode.USER_BANNED.getDefaultMessage(),
                HttpStatus.FORBIDDEN.value());
    }
}
