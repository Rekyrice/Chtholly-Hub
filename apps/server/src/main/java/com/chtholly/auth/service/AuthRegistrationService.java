package com.chtholly.auth.service;

import com.chtholly.auth.api.dto.AuthResponse;
import com.chtholly.auth.api.dto.RegisterRequest;
import com.chtholly.auth.model.ClientInfo;
import com.chtholly.auth.model.IdentifierType;
import com.chtholly.auth.verification.VerificationScene;
import com.chtholly.auth.verification.VerificationService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import com.chtholly.user.domain.User;
import com.chtholly.user.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Coordinates handle and verified-identifier registration use cases. */
@Service
public class AuthRegistrationService {

    private final UserService userService;
    private final VerificationService verificationService;
    private final PasswordEncoder passwordEncoder;
    private final AuthIdentityPolicy identityPolicy;
    private final AuthTokenLifecycleService tokenLifecycleService;
    private final AuthRegistrationSideEffectCoordinator sideEffectCoordinator;

    /** Creates the registration use case. */
    public AuthRegistrationService(
            UserService userService,
            VerificationService verificationService,
            PasswordEncoder passwordEncoder,
            AuthIdentityPolicy identityPolicy,
            AuthTokenLifecycleService tokenLifecycleService,
            AuthRegistrationSideEffectCoordinator sideEffectCoordinator) {
        this.userService = userService;
        this.verificationService = verificationService;
        this.passwordEncoder = passwordEncoder;
        this.identityPolicy = identityPolicy;
        this.tokenLifecycleService = tokenLifecycleService;
        this.sideEffectCoordinator = sideEffectCoordinator;
    }

    /** Registers a user and preserves token, audit, and event ordering. */
    @Transactional
    public AuthResponse register(
            RegisterRequest request,
            ClientInfo clientInfo) {
        if (!request.agreeTerms()) {
            throw new BusinessException(ErrorCode.TERMS_NOT_ACCEPTED);
        }
        if (request.identifierType() == IdentifierType.HANDLE) {
            return registerWithHandle(request, clientInfo);
        }
        return registerWithVerification(request, clientInfo);
    }

    private AuthResponse registerWithHandle(
            RegisterRequest request,
            ClientInfo clientInfo) {
        String handle = identityPolicy.normalizeHandle(request.handle());
        identityPolicy.validateHandle(handle);
        if (userService.existsByHandle(handle)) {
            throw new BusinessException(ErrorCode.HANDLE_EXISTS);
        }
        if (!StringUtils.hasText(request.password())) {
            throw new BusinessException(
                    ErrorCode.PASSWORD_POLICY_VIOLATION,
                    "密码不能为空");
        }
        identityPolicy.validatePassword(request.password());

        String nickname = StringUtils.hasText(request.nickname())
                ? request.nickname().trim()
                : identityPolicy.generateNickname();
        User user = User.builder()
                .handle(handle)
                .nickname(nickname)
                .avatar(null)
                .bio(null)
                .tagsJson("[]")
                .passwordHash(passwordEncoder.encode(
                        request.password()))
                .build();
        return createAndAuthenticate(user, handle, clientInfo);
    }

    private AuthResponse registerWithVerification(
            RegisterRequest request,
            ClientInfo clientInfo) {
        if (!StringUtils.hasText(request.identifier())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "账号不能为空");
        }
        if (!StringUtils.hasText(request.code())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码不能为空");
        }
        identityPolicy.validateIdentifier(
                request.identifierType(), request.identifier());
        String identifier = identityPolicy.normalizeIdentifier(
                request.identifierType(), request.identifier());
        if (identityPolicy.identifierExists(
                request.identifierType(), identifier)) {
            throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS);
        }
        AuthVerificationPolicy.requireSuccess(verificationService.verify(
                VerificationScene.REGISTER,
                identifier,
                request.code()));

        User user = User.builder()
                .phone(request.identifierType() == IdentifierType.PHONE
                        ? identifier : null)
                .email(request.identifierType() == IdentifierType.EMAIL
                        ? identifier : null)
                .nickname(identityPolicy.generateNickname())
                .avatar(null)
                .bio(null)
                .tagsJson("[]")
                .build();
        if (request.password() != null && !request.password().isEmpty()) {
            identityPolicy.validatePassword(request.password());
            user.setPasswordHash(passwordEncoder.encode(
                    request.password()));
        }
        return createAndAuthenticate(user, identifier, clientInfo);
    }

    private AuthResponse createAndAuthenticate(
            User user,
            String auditIdentifier,
            ClientInfo clientInfo) {
        userService.createUser(user);
        var token = tokenLifecycleService.issueForPendingRegistration(user);
        sideEffectCoordinator.afterCommit(
                user, auditIdentifier, clientInfo);
        return new AuthResponse(AuthResponseMapper.toUser(user), token);
    }
}
