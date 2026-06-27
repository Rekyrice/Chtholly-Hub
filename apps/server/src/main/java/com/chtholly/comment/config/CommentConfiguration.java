package com.chtholly.comment.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(CommentProperties.class)
public class CommentConfiguration {
}
