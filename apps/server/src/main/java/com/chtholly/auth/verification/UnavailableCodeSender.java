package com.chtholly.auth.verification;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fails verification delivery explicitly when no production adapter is configured.
 *
 * <p>The application can still start for password-based login and unrelated
 * APIs, while code-based flows fail without writing credentials to a local
 * file outside the explicit development profiles.</p>
 */
@Component
@Profile("!dev & !test")
public class UnavailableCodeSender implements CodeSender {

    @Override
    public void sendCode(
            VerificationScene scene,
            String identifier,
            String code,
            int expireMinutes) {
        throw new BusinessException(
                ErrorCode.INTERNAL_ERROR,
                "验证码发送服务尚未配置");
    }
}
