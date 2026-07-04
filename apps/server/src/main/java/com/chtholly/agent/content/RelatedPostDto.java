package com.chtholly.agent.content;

import java.util.List;

/**
 * Related post item derived from content analysis.
 *
 * @param id             post ID
 * @param title          post title
 * @param summary        analyzed summary
 * @param sharedEntities shared entity names
 */
public record RelatedPostDto(
        Long id,
        String title,
        String summary,
        List<String> sharedEntities
) {
}
