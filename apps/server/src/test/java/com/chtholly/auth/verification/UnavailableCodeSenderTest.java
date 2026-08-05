package com.chtholly.auth.verification;

import com.chtholly.common.exception.BusinessException;
import com.chtholly.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnavailableCodeSenderTest {

    @Test
    void nonDevelopmentProfilesKeepAFailingAdapterInsteadOfLeakingCodes() {
        Profile profile = UnavailableCodeSender.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("!dev & !test");
        assertThatThrownBy(() -> new UnavailableCodeSender().sendCode(
                        VerificationScene.REGISTER,
                        "owner@example.com",
                        "829104",
                        5))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.INTERNAL_ERROR))
                .hasMessage("验证码发送服务尚未配置");
    }
}
