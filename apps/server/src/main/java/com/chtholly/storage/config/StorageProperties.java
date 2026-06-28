package com.chtholly.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /** local 或 oss */
    private String type = "local";

    private Local local = new Local();

    @Data
    public static class Local {
        /** 本地文件存储根目录 */
        private String basePath = "./uploads";
        /** 对外访问 URL 前缀 */
        private String publicUrlPrefix = "/uploads";
    }
}
