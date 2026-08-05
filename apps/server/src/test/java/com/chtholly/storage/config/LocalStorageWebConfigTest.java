package com.chtholly.storage.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LocalStorageWebConfigTest {

    @TempDir
    Path storageRoot;

    @Test
    void localResourceHandler_blocksUnsafeLegacyPostBodiesButKeepsSafeTextContentReadable()
            throws Exception {
        Path postDirectory = Files.createDirectories(storageRoot.resolve("posts/42"));
        Files.writeString(postDirectory.resolve("content.html"), "<script>alert(1)</script>");
        Files.writeString(postDirectory.resolve("content.htm"), "<script>alert(1)</script>");
        Files.writeString(postDirectory.resolve("content.xhtml"), "<script>alert(1)</script>");
        Files.writeString(postDirectory.resolve("content.svg"), "<svg onload=alert(1)></svg>");
        Files.writeString(postDirectory.resolve("content"), "<script>alert(1)</script>");
        Files.writeString(postDirectory.resolve("content.md"), "# safe");
        Files.writeString(postDirectory.resolve("content.txt"), "safe text");
        Files.writeString(postDirectory.resolve("content.json"), "{\"safe\":true}");

        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(MvcTestConfiguration.class, LocalStorageWebConfig.class);
            TestPropertyValues.of(
                    "storage.local.base-path=" + storageRoot,
                    "storage.local.public-url-prefix=/uploads")
                    .applyTo(context);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

            mvc.perform(get("/uploads/posts/42/content.html"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/uploads/posts/42/content.htm"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/uploads/posts/42/content.xhtml"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/uploads/posts/42/content.svg"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/uploads/posts/42/content"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/uploads/posts/42/content.md"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("# safe"));
            mvc.perform(get("/uploads/posts/42/content.txt"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("safe text"));
            mvc.perform(get("/uploads/posts/42/content.json"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"safe\":true}"));
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableConfigurationProperties(StorageProperties.class)
    static class MvcTestConfiguration {
    }
}
