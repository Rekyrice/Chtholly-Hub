package com.chtholly.comment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 评论模块配置，绑定 {@code comment.*}。 */
@Data
@ConfigurationProperties(prefix = "comment")
public class CommentProperties {

    /** 同一用户每分钟最多发表评论数。 */
    private int rateLimitPerMinute = 5;

    /** 敏感词列表，命中时替换为 ***。 */
    private List<String> sensitiveWords = new ArrayList<>();
}
