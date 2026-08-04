package com.chtholly.auth.service;

import com.chtholly.auth.api.dto.SendCodeRequest;
import com.chtholly.auth.api.dto.SendCodeResponse;
import com.chtholly.auth.verification.SendCodeResult;
import com.chtholly.auth.verification.VerificationScene;
import com.chtholly.auth.verification.VerificationService;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import org.springframework.stereotype.Service;

/** Validates identifier eligibility before issuing authentication codes. */
@Service
public class AuthVerificationCodeService {

    private final AuthIdentityPolicy identityPolicy;
    private final VerificationService verificationService;

    /** Creates the verification-code use case. */
    public AuthVerificationCodeService(
            AuthIdentityPolicy identityPolicy,
            VerificationService verificationService) {
        this.identityPolicy = identityPolicy;
        this.verificationService = verificationService;
    }

    /** Sends one code using the existing scene-specific existence policy. */
    public SendCodeResponse sendCode(SendCodeRequest request) {
        identityPolicy.validateIdentifier(
                request.identifierType(), request.identifier());
        String normalized = identityPolicy.normalizeIdentifier(
                request.identifierType(), request.identifier());
        boolean exists = identityPolicy.identifierExists(
                request.identifierType(), normalized);
        if (request.scene() == VerificationScene.REGISTER && exists) {
            throw new BusinessException(ErrorCode.IDENTIFIER_EXISTS);
        }
        if ((request.scene() == VerificationScene.LOGIN
                || request.scene() == VerificationScene.RESET_PASSWORD)
                && !exists) {
            throw new BusinessException(ErrorCode.IDENTIFIER_NOT_FOUND);
        }
        SendCodeResult result = verificationService.sendCode(
                request.scene(), normalized);
        return new SendCodeResponse(
                result.identifier(), result.scene(), result.expireSeconds());
    }
}
