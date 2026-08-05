package com.chtholly.storage.config;

import com.chtholly.storage.config.StorageProperties.Local;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes safe local-storage objects below the configured public URL prefix.
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
                .addResourceLocations(location)
                .resourceChain(true)
                .addResolver(new SafeLocalStorageResourceResolver());
    }

    /**
     * Rejects executable legacy post bodies while retaining ordinary media delivery.
     */
    static final class SafeLocalStorageResourceResolver extends PathResourceResolver {

        private static final Pattern LEGACY_POST_CONTENT = Pattern.compile(
                "(?i)^posts/[1-9][0-9]*/content(?:\\.([^/]*))?$");
        private static final Set<String> SAFE_TEXT_EXTENSIONS = Set.of("md", "txt", "json");

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Matcher legacyContent = LEGACY_POST_CONTENT.matcher(resourcePath);
            if (legacyContent.matches() && !isSafeTextExtension(legacyContent.group(1))) {
                return null;
            }
            return super.getResource(resourcePath, location);
        }

        private static boolean isSafeTextExtension(String extension) {
            return extension != null
                    && SAFE_TEXT_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
        }
    }
}
