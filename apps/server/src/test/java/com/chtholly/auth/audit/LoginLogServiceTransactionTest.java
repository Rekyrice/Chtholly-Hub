package com.chtholly.auth.audit;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class LoginLogServiceTransactionTest {

    @Test
    void successAuditUsesANewTransaction() throws Exception {
        Transactional transactional = LoginLogService.class
                .getMethod(
                        "recordSuccess",
                        Long.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }
}
