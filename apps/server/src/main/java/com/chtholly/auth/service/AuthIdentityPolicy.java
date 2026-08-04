package com.chtholly.auth.service;

import com.chtholly.auth.config.AuthProperties;
import com.chtholly.auth.model.IdentifierType;
import com.chtholly.auth.util.IdentifierValidator;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Shared identity normalization and credential policy for authentication use cases. */
@Component
public class AuthIdentityPolicy {

    private final UserService userService;
    private final AuthProperties authProperties;

    /**
     * Creates the shared identity policy.
     *
     * @param userService user lookup service
     * @param authProperties authentication policy properties
     */
    public AuthIdentityPolicy(
            UserService userService,
            AuthProperties authProperties) {
        this.userService = userService;
        this.authProperties = authProperties;
    }

    /** Validates a phone, email, or handle using the existing API policy. */
    public void validateIdentifier(IdentifierType type, String identifier) {
        if (type == IdentifierType.PHONE
                && !IdentifierValidator.isValidPhone(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式错误");
        }
        if (type == IdentifierType.EMAIL
                && !IdentifierValidator.isValidEmail(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式错误");
        }
        if (type == IdentifierType.HANDLE) {
            validateHandle(identifier);
        }
    }

    /** Validates one normalized handle. */
    public void validateHandle(String handle) {
        if (!IdentifierValidator.isValidHandle(handle)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "用户名需 3-32 字符，仅支持字母、数字、下划线，且不能以数字开头");
        }
    }

    /** Normalizes and validates the non-empty shape of a handle. */
    public String normalizeHandle(String handle) {
        if (!StringUtils.hasText(handle)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        return handle.trim();
    }

    /** Applies the configured password complexity policy. */
    public void validatePassword(String password) {
        if (!StringUtils.hasText(password)) {
            throw new BusinessException(
                    ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "密码不能为空");
        }
        String trimmed = password.trim();
        int minLength = authProperties.getPassword().getMinLength();
        if (trimmed.length() < minLength) {
            throw new BusinessException(
                    ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "密码长度至少" + minLength + "位");
        }
        boolean hasLetter = trimmed.chars().anyMatch(Character::isLetter);
        boolean hasDigit = trimmed.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(
                    ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "密码需包含字母和数字");
        }
    }

    /** Returns whether the normalized identifier already exists. */
    public boolean identifierExists(
            IdentifierType type,
            String identifier) {
        return switch (type) {
            case PHONE -> userService.existsByPhone(identifier);
            case EMAIL -> userService.existsByEmail(identifier);
            case HANDLE -> userService.existsByHandle(identifier);
        };
    }

    /** Finds a user by one normalized identifier. */
    public Optional<User> findUserByIdentifier(
            IdentifierType type,
            String identifier) {
        return switch (type) {
            case PHONE -> userService.findByPhone(identifier);
            case EMAIL -> userService.findByEmail(identifier);
            case HANDLE -> userService.findByHandle(identifier);
        };
    }

    /** Finds a user by its identifier. */
    public Optional<User> findUserById(long userId) {
        return userService.findById(userId);
    }

    /** Normalizes identifiers without changing the existing case policy. */
    public String normalizeIdentifier(
            IdentifierType type,
            String identifier) {
        return switch (type) {
            case PHONE -> identifier.trim();
            case EMAIL -> identifier.trim().toLowerCase(Locale.ROOT);
            case HANDLE -> normalizeHandle(identifier);
        };
    }

    /** Generates the existing anonymous nickname shape. */
    public String generateNickname() {
        return "Chtholly用户" + UUID.randomUUID().toString().substring(0, 8);
    }
}
