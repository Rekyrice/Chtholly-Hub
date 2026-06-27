package com.chtholly.bangumi.job;

import com.chtholly.bangumi.client.BangumiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每日拉取 Bangumi 放送表（轻量预热，详细同步在搜索 miss 时触发）。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "bangumi.sync.enabled", havingValue = "true")
public class BangumiSyncJob {

    private final BangumiClient bangumiClient;

    @Scheduled(cron = "${bangumi.sync.cron:0 0 4 * * *}")
    public void syncCalendar() {
        try {
            int days = bangumiClient.fetchCalendar().map(JsonNode::size).orElse(0);
            log.info("Bangumi 日历同步完成，weekdays={}", days);
        } catch (Exception e) {
            log.warn("Bangumi 日历同步失败: {}", e.getMessage());
        }
    }
}
