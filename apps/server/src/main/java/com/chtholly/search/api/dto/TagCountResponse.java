package com.chtholly.search.api.dto;

/**
 * Tag count item derived from Elasticsearch terms aggregation.
 *
 * @param id stable string id for frontend list keys
 * @param name display name
 * @param slug URL slug
 * @param usageCount number of indexed posts containing this tag
 */
public record TagCountResponse(
        String id,
        String name,
        String slug,
        long usageCount
) {
}
