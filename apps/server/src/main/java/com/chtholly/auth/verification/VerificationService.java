package com.chtholly.auth.verification;

import com.chtholly.auth.config.AuthProperties;
import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;

/**
 * Coordinates verification-code quota reservation, persistence and delivery.
 *
 * <p>A send is considered accepted only after both code persistence and the
 * configured delivery adapter succeed. Failures invalidate any stored code and
 * compensate the matching quota reservation without hiding the original
 * failure.</p>
 */
@Service
public class VerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationCodeStore codeStore;
    private final CodeSender codeSender;
    private final VerificationSendGuard sendGuard;
    private final AuthProperties properties;

    /**
     * Creates the verification-code application service.
     *
     * @param codeStore authoritative short-lived code store
     * @param codeSender configured delivery adapter
     * @param sendGuard atomic quota reservation service
     * @param properties authentication settings
     */
    public VerificationService(
            VerificationCodeStore codeStore,
            CodeSender codeSender,
            VerificationSendGuard sendGuard,
            AuthProperties properties) {
        this.codeStore = codeStore;
        this.codeSender = codeSender;
        this.sendGuard = sendGuard;
        this.properties = properties;
    }

    /**
     * Reserves quota, persists a random code and delivers it to an identifier.
     *
     * @param scene verification-code purpose
     * @param identifier phone number or email destination
     * @return delivery metadata
     * @throws BusinessException when parameters or configured quotas reject the request
     */
    public SendCodeResult sendCode(
            VerificationScene scene,
            String identifier) {
        if (scene == null || !StringUtils.hasText(identifier)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "请提供正确的验证码发送参数");
        }
        AuthProperties.Verification cfg = properties.getVerification();
        VerificationSendGuard.Reservation reservation = sendGuard.reserve(
                scene,
                identifier,
                cfg.getSendInterval(),
                cfg.getDailyLimit());

        VerificationCodeStore.IssuedCode issuedCode = null;
        try {
            String code = generateNumericCode(cfg.getCodeLength());
            issuedCode = VerificationCodeStore.IssuedCode.issue(code);
            codeStore.saveCode(
                    scene.name(),
                    identifier,
                    issuedCode,
                    cfg.getTtl(),
                    cfg.getMaxAttempts());
            codeSender.sendCode(
                    scene,
                    identifier,
                    issuedCode.value(),
                    (int) cfg.getTtl().toMinutes());
        } catch (RuntimeException failure) {
            if (issuedCode != null) {
                invalidateUndeliveredCode(
                        scene, identifier, issuedCode.version(), failure);
            }
            compensateQuota(reservation, failure);
            throw failure;
        }
        return new SendCodeResult(
                identifier,
                scene,
                (int) cfg.getTtl().toSeconds());
    }

    /**
     * Checks whether a verification code is valid and still usable.
     *
     * @param scene verification-code purpose
     * @param identifier phone number or email destination
     * @param code user-provided code
     * @return verification result and attempt state
     * @throws BusinessException when parameters are incomplete
     */
    public VerificationCheckResult verify(
            VerificationScene scene,
            String identifier,
            String code) {
        if (scene == null
                || !StringUtils.hasText(identifier)
                || !StringUtils.hasText(code)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "验证码校验参数不完整");
        }
        return codeStore.verify(scene.name(), identifier, code);
    }

    /**
     * Invalidates a stored verification code.
     *
     * @param scene verification-code purpose
     * @param identifier phone number or email destination
     */
    public void invalidate(
            VerificationScene scene,
            String identifier) {
        codeStore.invalidate(scene.name(), identifier);
    }

    private void invalidateUndeliveredCode(
            VerificationScene scene,
            String identifier,
            String version,
            RuntimeException originalFailure) {
        try {
            codeStore.invalidateIfCurrent(scene.name(), identifier, version);
        } catch (RuntimeException compensationFailure) {
            originalFailure.addSuppressed(compensationFailure);
        }
    }

    private void compensateQuota(
            VerificationSendGuard.Reservation reservation,
            RuntimeException originalFailure) {
        try {
            sendGuard.compensate(reservation);
        } catch (RuntimeException compensationFailure) {
            originalFailure.addSuppressed(compensationFailure);
        }
    }

    private static String generateNumericCode(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append(RANDOM.nextInt(10));
        }
        return builder.toString();
    }
}
