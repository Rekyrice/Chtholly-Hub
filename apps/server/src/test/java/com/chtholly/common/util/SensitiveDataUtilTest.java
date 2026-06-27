package com.chtholly.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataUtilTest {

    @Test
    void masksPhoneKeepingFirstThreeAndLastFour() {
        assertThat(SensitiveDataUtil.maskPhone("13812341234")).isEqualTo("138****1234");
    }

    @Test
    void masksEmailKeepingFirstCharAndDomain() {
        assertThat(SensitiveDataUtil.maskEmail("rrice@example.com")).isEqualTo("r***rice@example.com");
    }

    @Test
    void returnsNullForNullInput() {
        assertThat(SensitiveDataUtil.maskPhone(null)).isNull();
        assertThat(SensitiveDataUtil.maskEmail(null)).isNull();
    }
}
