package com.chtholly.storage.config;

import com.chtholly.storage.config.StorageProperties.Local;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地存储模式下，通过 /uploads/** 提供静态文件访问。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageWebConfig implements WebMvcConfigurer {

    private final StorageProperties props;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Local local = props.getLocal();
        String prefix = local.getPublicUrlPrefix().replaceAll("/$", "");
        Path basePath = Paths.get(local.getBasePath()).toAbsolutePath().normalize();
        String location = basePath.toUri().toString();
        if (!location.endsWith("/")) {
            location += "/";
        }
        registry.addResourceHandler(prefix + "/**")
                .addResourceLocations(location);
    }
}
