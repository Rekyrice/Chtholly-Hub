package com.chtholly.post.feed;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 关注时间线（Following Feed）配置。
 */
@Data
@ConfigurationProperties(prefix = "feed")
public class FeedTimelineProperties {

    private BigV bigv = new BigV();
    private Timeline timeline = new Timeline();

    @Data
    public static class BigV {
        /** 粉丝数达到此阈值视为大 V，发帖时不推模式写入粉丝 timeline。 */
        private int threshold = 1000;
    }

    @Data
    public static class Timeline {
        /** timeline ZSet 保留天数，超出部分 ZREMRANGEBYSCORE 清理。 */
        private int retentionDays = 30;
        /** 拉模式读取大 V 最近多少小时内的文章。 */
        private int bigvPullHours = 24;
        /** 大 V 近期文章 Redis 缓存秒数。 */
        private int bigvCacheSeconds = 300;
    }
}
