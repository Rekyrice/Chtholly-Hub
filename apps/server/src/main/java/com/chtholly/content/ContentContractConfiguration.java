package com.chtholly.content;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Provides the neutral content intelligence contract when content extensions are disabled.
 */
@Configuration
public class ContentContractConfiguration {

    /**
     * Provides an empty content intelligence reader for deployments without content extensions.
     *
     * @return reader returning no content intelligence results
     */
    @Bean
    @ConditionalOnProperty(
            prefix = "agent.extensions.content",
            name = "enabled",
            havingValue = "false")
    public ContentIntelligenceReader emptyContentIntelligenceReader() {
        return new ContentIntelligenceReader() {
            @Override
            public ContentAnalysis getAnalysis(Long postId) {
                return null;
            }

            @Override
            public ContentAnalysis getAnalysisBySlug(String slug) {
                return null;
            }

            @Override
            public List<RelatedPostDto> getRelatedPosts(Long postId) {
                return List.of();
            }
        };
    }
}
