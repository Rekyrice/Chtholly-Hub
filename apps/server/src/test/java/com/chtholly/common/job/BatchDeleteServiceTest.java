package com.chtholly.common.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BatchDeleteServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private BatchDeleteService service;

    @BeforeEach
    void setUp() {
        service = new BatchDeleteService(jdbcTemplate);
    }

    @Test
    void deletesInBatchesUntilNoRowsLeft() {
        when(jdbcTemplate.update(any(), eq(90)))
                .thenReturn(1000, 1000, 200, 0);

        int total = service.deleteInBatches(
                "DELETE FROM login_logs WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY) LIMIT 1000",
                90);

        assertThat(total).isEqualTo(2200);
        verify(jdbcTemplate, times(4)).update(any(), eq(90));
    }
}
