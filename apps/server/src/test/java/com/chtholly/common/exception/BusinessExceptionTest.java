package com.chtholly.common.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    void internalErrorDefaultsToHttp500() {
        assertThat(new BusinessException(ErrorCode.INTERNAL_ERROR, "upstream failed").getHttpStatus())
                .isEqualTo(500);
    }

    @Test
    void validationErrorStillDefaultsToHttp400() {
        assertThat(new BusinessException(ErrorCode.BAD_REQUEST, "invalid").getHttpStatus())
                .isEqualTo(400);
    }
}
