package com.chtholly.seed;

import com.chtholly.storage.StorageService;
import com.chtholly.storage.config.OssProperties;
import com.chtholly.storage.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 将种子 Markdown 上传到对象存储，返回前端可 fetch 的公网 URL。
 */
@Component
@RequiredArgsConstructor
public class SeedContentPublisher {

    private final StorageService storageService;
    private final StorageProperties storageProperties;
    private final OssProperties ossProperties;

    public String publishMarkdown(String objectKey, String markdown) throws IOException {
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        storageService.uploadObject(
                objectKey,
                new ByteArrayInputStream(bytes),
                "text/markdown; charset=utf-8",
                bytes.length);
        return publicUrl(objectKey);
    }

    private String publicUrl(String objectKey) {
        if ("oss".equalsIgnoreCase(storageProperties.getType())) {
            String domain = ossProperties.getPublicDomain();
            if (domain != null && !domain.isBlank()) {
                return domain.replaceAll("/$", "") + "/" + objectKey;
            }
            return "https://" + ossProperties.getBucket() + "." + ossProperties.getEndpoint() + "/" + objectKey;
        }
        String prefix = storageProperties.getLocal().getPublicUrlPrefix().replaceAll("/$", "");
        return prefix + "/" + objectKey;
    }
}
