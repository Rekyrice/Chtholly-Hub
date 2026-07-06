package com.chtholly.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.chtholly.admin.security.UserBanService;
import com.chtholly.auth.api.dto.AuthResponse;
import com.chtholly.auth.api.dto.AuthUserResponse;
import com.chtholly.auth.api.dto.LoginRequest;
import com.chtholly.auth.api.dto.PasswordResetRequest;
import com.chtholly.auth.api.dto.RegisterRequest;
import com.chtholly.auth.api.dto.SendCodeRequest;
import com.chtholly.auth.api.dto.SendCodeResponse;
import com.chtholly.auth.api.dto.TokenRefreshRequest;
import com.chtholly.auth.api.dto.TokenResponse;
import com.chtholly.auth.audit.LoginFailureReason;
import com.chtholly.auth.audit.LoginLogService;
import com.chtholly.auth.event.UserRegisteredEvent;
import com.chtholly.auth.security.LoginFailureGuard;
import com.chtholly.auth.config.AuthProperties;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.auth.model.ClientInfo;
import com.chtholly.auth.model.IdentifierType;
import com.chtholly.auth.token.JwtService;
import com.chtholly.auth.token.RefreshTokenStore;
import com.chtholly.auth.token.TokenPair;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import com.chtholly.auth.util.IdentifierValidator;
import com.chtholly.auth.verification.SendCodeResult;
import com.chtholly.auth.verification.VerificationCheckResult;
import com.chtholly.auth.verification.VerificationCodeStatus;
import com.chtholly.auth.verification.VerificationScene;
import com.chtholly.auth.verification.VerificationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * 认证业务服务。
 * <p>
 * 职责：发送验证码、注册、登录、刷新令牌、登出、重置密码、查询当前用户信息。
 * 安全策略：
 * - 账号格式校验（手机号/邮箱）；
 * - 验证码状态检查（过期/错误/尝试超限）；
 * - 密码复杂度校验（长度与字符类型）；
 * - Refresh Token 白名单存储与轮换，登出/重置密码后失效旧令牌；
 * 审计：记录注册/登录成功与失败，包含渠道、IP、UA。
 * 令牌：签发 RS256 的 Access/Refresh JWT，携带 uid、token_type、jti。
 * 依赖：UserService、VerificationService、PasswordEncoder、JwtService、RefreshTokenStore、LoginLogService、AuthProperties。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserService userService;
    private final VerificationService verificationService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final LoginLogService loginLogService;
    private final LoginFailureGuard loginFailureGuard;
    private final AuthProperties authProperties;
    private final UserBanService userBanService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 发送验证码并返回过期信息。
     * <p>
     * 注册场景要求标识不存在；登录/重置密码场景要求标识存在。
     *
     * @param request 请求体，包含：标识类型与值、场景。
     * @return 响应体，包含目标标识、场景与验证码过期秒数。
     * @throws BusinessException 当标识格式错误或存在性不符合场景要求时抛出。
     */
    public SendCodeResponse sendCode(SendCodeRequest request) {
        validateIdentifier(request.identifierType(), request.identifier());
        String normalized = normalizeIdentifier(request.identifierType(), request.identifier());
        boolean exists = identifierExists(request.identifierType(), normalized);
        if (request.scene() == VerificationScene.REGISTER && exists) {
            throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS);
        }
        if ((request.scene() == VerificationScene.LOGIN || request.scene() == VerificationScene.RESET_PASSWORD) && !exists) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND);
        }
        SendCodeResult result = verificationService.sendCode(request.scene(), normalized);
        return new SendCodeResponse(result.identifier(), result.scene(), result.expireSeconds());
    }

    /**
     * 注册用户并签发令牌。
     * <p>
     * 验证标识与验证码，创建用户（可选设置密码），记录审计，签发令牌对并保存刷新令牌白名单。
     *
     * @param request    注册请求，包含：标识类型与值、验证码、可选密码、是否同意协议。
     * @param clientInfo 客户端信息（IP/UA），用于登录审计。
     * @return 认证响应，包含用户信息与令牌对。
     * @throws BusinessException 当未同意协议、标识冲突、验证码失败、密码不合规时抛出。
     */
    public AuthResponse register(RegisterRequest request, ClientInfo clientInfo) {
        if (!request.agreeTerms()) {
            throw new BusinessException(ErrorCode.TERMS_NOT_ACCEPTED);
        }
        if (request.identifierType() == IdentifierType.HANDLE) {
            return registerWithHandle(request, clientInfo);
        }
        return registerWithVerification(request, clientInfo);
    }

    private AuthResponse registerWithHandle(RegisterRequest request, ClientInfo clientInfo) {
        String handle = normalizeHandle(request.handle());
        validateHandle(handle);
        if (userService.existsByHandle(handle)) {
            throw new BusinessException(ErrorCode.HANDLE_EXISTS);
        }
        if (!StringUtils.hasText(request.password())) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码不能为空");
        }
        validatePassword(request.password());

        String nickname = StringUtils.hasText(request.nickname())
                ? request.nickname().trim()
                : generateNickname();

        User user = User.builder()
                .handle(handle)
                .nickname(nickname)
                .avatar(null)
                .bio(null)
                .tagsJson("[]")
                .passwordHash(passwordEncoder.encode(request.password().trim()))
                .build();

        userService.createUser(user);
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        storeRefreshToken(user.getId(), tokenPair);
        loginLogService.recordSuccess(user.getId(), handle, "REGISTER", clientInfo.ip(), clientInfo.userAgent());
        publishUserRegistered(user);
        return new AuthResponse(mapUser(user), mapToken(tokenPair));
    }

    private AuthResponse registerWithVerification(RegisterRequest request, ClientInfo clientInfo) {
        if (!StringUtils.hasText(request.identifier())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号不能为空");
        }
        if (!StringUtils.hasText(request.code())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码不能为空");
        }
        validateIdentifier(request.identifierType(), request.identifier());
        String identifier = normalizeIdentifier(request.identifierType(), request.identifier());
        if (identifierExists(request.identifierType(), identifier)) {
            throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS);
        }
        ensureVerificationSuccess(verificationService.verify(VerificationScene.REGISTER, identifier, request.code()));

        User user = User.builder()
                .phone(request.identifierType() == IdentifierType.PHONE ? identifier : null)
                .email(request.identifierType() == IdentifierType.EMAIL ? identifier : null)
                .nickname(generateNickname())
                .avatar(null)
                .bio(null)
                .tagsJson("[]")
                .build();

        if (StringUtils.hasText(request.password())) {
            validatePassword(request.password());
            user.setPasswordHash(passwordEncoder.encode(request.password().trim()));
        }

        userService.createUser(user);
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        storeRefreshToken(user.getId(), tokenPair);
        loginLogService.recordSuccess(user.getId(), identifier, "REGISTER", clientInfo.ip(), clientInfo.userAgent());
        publishUserRegistered(user);
        return new AuthResponse(mapUser(user), mapToken(tokenPair));
    }

    private void publishUserRegistered(User user) {
        try {
            eventPublisher.publishEvent(new UserRegisteredEvent(user));
        } catch (Exception e) {
            log.warn("UserRegisteredEvent failed, userId={}: {}", user.getId(), e.getMessage());
        }
    }

    /**
     * 登录并签发令牌。
     * <p>
     * 支持密码或验证码通道；成功后记录审计，签发令牌对并保存刷新令牌白名单。
     *
     * @param request    登录请求，包含：标识类型与值、密码或验证码（二选一）。
     * @param clientInfo 客户端信息（IP/UA），用于登录审计。
     * @return 认证响应，包含用户信息与令牌对。
     * @throws BusinessException 当用户不存在、凭证错误或请求不合法时抛出。
     */
    public AuthResponse login(LoginRequest request, ClientInfo clientInfo) {
        String identifier = resolveLoginIdentifier(request);
        try {
            loginFailureGuard.assertNotLocked(identifier, clientInfo.ip());
        } catch (BusinessException ex) {
            loginLogService.recordFailure(null, identifier, "PASSWORD", clientInfo.ip(), clientInfo.userAgent(),
                    LoginFailureReason.ACCOUNT_LOCKED);
            throw ex;
        }

        Optional<User> userOptional = findUserByIdentifier(request.identifierType(), identifier);
        if (userOptional.isEmpty()) {
            loginLogService.recordFailure(null, identifier, resolveLoginChannel(request), clientInfo.ip(),
                    clientInfo.userAgent(), LoginFailureReason.ACCOUNT_NOT_FOUND);
            if (StringUtils.hasText(request.password())) {
                loginFailureGuard.onFailure(identifier, clientInfo.ip());
            }
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND);
        }
        User user = userOptional.get();
        String channel;
        if (StringUtils.hasText(request.password())) {
            channel = "PASSWORD";
            if (!StringUtils.hasText(user.getPasswordHash())
                    || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
                loginLogService.recordFailure(user.getId(), identifier, channel, clientInfo.ip(),
                        clientInfo.userAgent(), LoginFailureReason.WRONG_PASSWORD);
                loginFailureGuard.onFailure(identifier, clientInfo.ip());
                throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
            }
        } else if (StringUtils.hasText(request.code())) {
            channel = "CODE";
            try {
                ensureVerificationSuccess(verificationService.verify(VerificationScene.LOGIN, identifier, request.code()));
            } catch (BusinessException ex) {
                loginLogService.recordFailure(user.getId(), identifier, channel, clientInfo.ip(),
                        clientInfo.userAgent(), null);
                loginFailureGuard.onFailure(identifier, clientInfo.ip());
                throw ex;
            }
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请提供验证码或密码");
        }
        userBanService.assertNotBanned(user);
        loginFailureGuard.onSuccess(identifier);
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        storeRefreshToken(user.getId(), tokenPair);
        loginLogService.recordSuccess(user.getId(), identifier, channel, clientInfo.ip(), clientInfo.userAgent());
        return new AuthResponse(mapUser(user), mapToken(tokenPair));
    }

    private String resolveLoginIdentifier(LoginRequest request) {
        if (request.identifierType() == IdentifierType.HANDLE) {
            String handle = normalizeHandle(request.identifier());
            validateHandle(handle);
            return handle;
        }
        validateIdentifier(request.identifierType(), request.identifier());
        return normalizeIdentifier(request.identifierType(), request.identifier());
    }

    private String resolveLoginChannel(LoginRequest request) {
        return StringUtils.hasText(request.password()) ? "PASSWORD" : "CODE";
    }

    /**
     * 使用刷新令牌获取新的令牌对。
     * <p>
     * 校验刷新令牌类型与白名单有效性，签发新令牌后撤销旧刷新令牌并存储新令牌。
     *
     * @param request 刷新请求，包含：refreshToken。
     * @return 新的令牌响应。
     * @throws BusinessException 当刷新令牌无效或用户不存在时抛出。
     */
    public TokenResponse refresh(TokenRefreshRequest request) {
        Jwt jwt = decodeRefreshToken(request.refreshToken());

        if (!Objects.equals("refresh", jwtService.extractTokenType(jwt))) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        long userId = jwtService.extractUserId(jwt);
        String tokenId = jwtService.extractTokenId(jwt);

        if (!refreshTokenStore.isTokenValid(userId, tokenId)) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        User user = findUserById(userId).orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        if (user.getBannedAt() != null) {
            refreshTokenStore.revokeToken(userId, tokenId);
            throw userBanService.bannedException();
        }
        TokenPair tokenPair = jwtService.issueTokenPair(user);
        refreshTokenStore.revokeToken(userId, tokenId);
        storeRefreshToken(userId, tokenPair);

        return mapToken(tokenPair);
    }

    /**
     * 登出：撤销指定刷新令牌。
     *
     * @param refreshToken 刷新令牌字符串；若解析为合法刷新令牌则撤销其白名单记录。
     */
    public void logout(String refreshToken) {
        decodeRefreshTokenSafely(refreshToken).ifPresent(jwt -> {
            if (Objects.equals("refresh", jwtService.extractTokenType(jwt))) {
                long userId = jwtService.extractUserId(jwt);
                String tokenId = jwtService.extractTokenId(jwt);
                refreshTokenStore.revokeToken(userId, tokenId);
            }
        });
    }

    /**
     * 使用验证码重置密码并使刷新令牌失效。
     *
     * @param request 重置请求，包含：标识类型与值、验证码、新密码。
     * @throws BusinessException 当标识不存在、验证码失败或密码策略不满足时抛出。
     */
    public void resetPassword(PasswordResetRequest request) {
        validateIdentifier(request.identifierType(), request.identifier());
        validatePassword(request.newPassword());
        String identifier = normalizeIdentifier(request.identifierType(), request.identifier());
        User user = findUserByIdentifier(request.identifierType(), identifier)
                .orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        ensureVerificationSuccess(verificationService.verify(VerificationScene.RESET_PASSWORD, identifier, request.code()));
        user.setPasswordHash(passwordEncoder.encode(request.newPassword().trim()));
        userService.updatePassword(user);
        refreshTokenStore.revokeAll(user.getId());
    }

    /**
     * 查询用户概要信息。
     *
     * @param userId 用户 ID。
     * @return 用户概要响应。
     * @throws BusinessException 当用户不存在时抛出。
     */
    public AuthUserResponse me(long userId) {
        User user = findUserById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND));
        return mapUser(user);
    }

    /**
     * 保证验证码校验成功，否则按状态抛出对应业务异常。
     *
     * @param result 验证码校验结果。
     */
    private void ensureVerificationSuccess(VerificationCheckResult result) {
        if (result.isSuccess()) {
            return;
        }
        VerificationCodeStatus status = result.status();
        if (status == VerificationCodeStatus.NOT_FOUND || status == VerificationCodeStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.VERIFICATION_NOT_FOUND);
        }
        if (status == VerificationCodeStatus.MISMATCH) {
            throw new BusinessException(ErrorCode.VERIFICATION_MISMATCH);
        }
        if (status == VerificationCodeStatus.TOO_MANY_ATTEMPTS) {
            throw new BusinessException(ErrorCode.VERIFICATION_TOO_MANY_ATTEMPTS);
        }
        throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码校验失败");
    }

    /**
     * 校验标识（手机号/邮箱）的格式。
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 标识值。
     * @throws BusinessException 当格式不合法时抛出。
     */
    private void validateIdentifier(IdentifierType type, String identifier) {
        if (type == IdentifierType.PHONE && !IdentifierValidator.isValidPhone(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式错误");
        }
        if (type == IdentifierType.EMAIL && !IdentifierValidator.isValidEmail(identifier)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式错误");
        }
        if (type == IdentifierType.HANDLE) {
            validateHandle(identifier);
        }
    }

    private void validateHandle(String handle) {
        if (!IdentifierValidator.isValidHandle(handle)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "用户名需 3-32 字符，仅支持字母、数字、下划线，且不能以数字开头");
        }
    }

    private String normalizeHandle(String handle) {
        if (!StringUtils.hasText(handle)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名不能为空");
        }
        return handle.trim();
    }

    /**
     * 校验密码策略：非空、最小长度、必须包含字母和数字。
     *
     * @param password 明文密码。
     * @throws BusinessException 当密码不满足策略时抛出。
     */
    private void validatePassword(String password) {
        if (!StringUtils.hasText(password)) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码不能为空");
        }
        String trimmed = password.trim();
        if (trimmed.length() < authProperties.getPassword().getMinLength()) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码长度至少" + authProperties.getPassword().getMinLength() + "位");
        }
        boolean hasLetter = trimmed.chars().anyMatch(Character::isLetter);
        boolean hasDigit = trimmed.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException(ErrorCode.PASSWORD_POLICY_VIOLATION, "密码需包含字母和数字");
        }
    }

    /**
     * 判断标识是否已存在。
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 标识值（需为标准化格式）。
     * @return 是否存在。
     */
    private boolean identifierExists(IdentifierType type, String identifier) {
        return switch (type) {
            case PHONE -> userService.existsByPhone(identifier);
            case EMAIL -> userService.existsByEmail(identifier);
            case HANDLE -> userService.existsByHandle(identifier);
        };
    }

    /**
     * 根据标识查找用户。
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 标识值（需为标准化格式）。
     * @return 用户 Optional。
     */
    private Optional<User> findUserByIdentifier(IdentifierType type, String identifier) {
        return switch (type) {
            case PHONE -> userService.findByPhone(identifier);
            case EMAIL -> userService.findByEmail(identifier);
            case HANDLE -> userService.findByHandle(identifier);
        };
    }

    /**
     * 根据 ID 查找用户。
     *
     * @param userId 用户 ID。
     * @return 用户 Optional。
     */
    private Optional<User> findUserById(long userId) {
        return userService.findById(userId);
    }

    /**
     * 标准化标识文本：手机号去空格、邮箱转小写并去空格。
     *
     * @param type       标识类型：PHONE 或 EMAIL。
     * @param identifier 原始标识文本。
     * @return 标准化后的标识文本。
     */
    private String normalizeIdentifier(IdentifierType type, String identifier) {
        return switch (type) {
            case PHONE -> identifier.trim();
            case EMAIL -> identifier.trim().toLowerCase(Locale.ROOT);
            case HANDLE -> normalizeHandle(identifier);
        };
    }

    /**
     * 存储刷新令牌白名单记录。
     *
     * @param userId    用户 ID。
     * @param tokenPair 令牌对（含刷新令牌 ID 与过期时间）。
     */
    private void storeRefreshToken(Long userId, TokenPair tokenPair) {
        Duration ttl = Duration.between(Instant.now(), tokenPair.refreshTokenExpiresAt());
        if (ttl.isNegative()) {
            ttl = Duration.ZERO;
        }
        refreshTokenStore.storeToken(userId, tokenPair.refreshTokenId(), ttl);
    }

    /**
     * 映射用户实体到响应对象。
     *
     * @param user 用户实体。
     * @return 用户响应。
     */
    private AuthUserResponse mapUser(User user) {
        return new AuthUserResponse(
                user.getId(),
                user.getNickname(),
                user.getAvatar(),
                user.getPhone(),
                user.getHandle(),
                user.getBirthday(),
                user.getSchool(),
                user.getBio(),
                user.getGender(),
                user.getTagsJson(),
                user.getRole()
        );
    }

    /**
     * 映射令牌对到响应对象。
     *
     * @param tokenPair 令牌对。
     * @return 令牌响应。
     */
    private TokenResponse mapToken(TokenPair tokenPair) {
        return new TokenResponse(tokenPair.accessToken(), tokenPair.accessTokenExpiresAt(), tokenPair.refreshToken(), tokenPair.refreshTokenExpiresAt());
    }

    /**
     * 生成默认昵称。
     *
     * @return 随机昵称字符串。
     */
    private String generateNickname() {
        return "Chtholly用户" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 解码刷新令牌，失败时抛业务异常。
     *
     * @param refreshToken 刷新令牌字符串。
     * @return 解析得到的 JWT。
     * @throws BusinessException 当刷新令牌无法解析时抛出。
     */
    private Jwt decodeRefreshToken(String refreshToken) {
        try {
            return jwtService.decode(refreshToken);
        } catch (JwtException ex) {
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
    }

    /**
     * 安全解码刷新令牌，失败时返回空 Optional。
     *
     * @param refreshToken 刷新令牌字符串。
     * @return 成功时返回 JWT，失败时返回 Optional.empty()。
     */
    private Optional<Jwt> decodeRefreshTokenSafely(String refreshToken) {
        try {
            return Optional.of(jwtService.decode(refreshToken));
        } catch (JwtException ex) {
            return Optional.empty();
        }
    }
}
