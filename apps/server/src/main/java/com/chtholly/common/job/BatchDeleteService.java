package com.chtholly.common.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 通用分批 DELETE，避免长事务锁表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchDeleteService {

    static final int BATCH_SIZE = 1000;
    static final long SLEEP_MS = 100L;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 循环执行带 LIMIT 的 DELETE，直到无更多匹配行。
     *
     * @param deleteSql 必须包含 {@code LIMIT}（占位符由调用方写入具体数字）
     * @param args      SQL 参数（不含 batch size）
     * @return 累计删除行数
     */
    public int deleteInBatches(String deleteSql, Object... args) {
        int total = 0;
        int affected;
        do {
            affected = jdbcTemplate.update(deleteSql, args);
            total += affected;
            if (affected > 0) {
                sleepBetweenBatches();
            }
        } while (affected > 0);
        return total;
    }

    private void sleepBetweenBatches() {
        try {
            Thread.sleep(SLEEP_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Batch delete interrupted");
        }
    }
}
