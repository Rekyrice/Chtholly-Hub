package com.chtholly.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "spring.elasticsearch")
public class EsProperties {
    private List<String> uris;    // 支持多个 ES 节点：spring.elasticsearch.uris

    // 由于 配置文件里 xpack.security.enabled: false，所以不需要账号密码了
    private String username;      // spring.elasticsearch.username（可选）
    private String password;      // spring.elasticsearch.password（可选）

    /** 搜索索引副本数，开发默认 0，生产建议 1。 */
    private String replicas = "0";
    private Duration connectionTimeout = Duration.ofSeconds(5);
    private Duration socketTimeout = Duration.ofSeconds(30);

    // RAG 索引名来自 Spring AI 的配置
    @Value("${spring.ai.vectorstore.elasticsearch.index-name:}")
    private String index;         // e.g. chtholly-ai-index

    // 兼容旧代码：返回第一个 URI 作为 host
    public String getHost() {
        return (uris == null || uris.isEmpty()) ? null : uris.getFirst();
    }
}
