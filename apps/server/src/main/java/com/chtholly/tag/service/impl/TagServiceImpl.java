package com.chtholly.tag.service.impl;

import com.chtholly.post.util.SlugUtils;
import com.chtholly.tag.api.dto.TagResponse;
import com.chtholly.tag.mapper.TagMapper;
import com.chtholly.tag.model.Tag;
import com.chtholly.tag.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Default implementation of {@link TagService}.
 * Maintains the tag catalog and synchronizes usage counts when published posts change.
 */
@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagMapper tagMapper;

    /**
     * Lists tags ordered by usage count.
     *
     * @param limit maximum number of tags to return (clamped to 1–200)
     * @return tag summaries sorted by popularity
     */
    @Override
    @Transactional(readOnly = true)
    public List<TagResponse> listTags(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return tagMapper.listOrderByUsage(safeLimit).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Reconciles tag usage counts after a published post's tags are updated.
     *
     * @param creatorId post author ID used when upserting new tags
     * @param oldTags previous tag names on the post
     * @param newTags updated tag names on the post
     */
    @Override
    @Transactional
    public void syncPublishedPostTags(long creatorId, List<String> oldTags, List<String> newTags) {
        Set<String> before = normalize(oldTags);
        Set<String> after = normalize(newTags);

        for (String removed : before) {
            if (!after.contains(removed)) {
                tagMapper.decrementUsage(removed);
            }
        }

        for (String added : after) {
            if (!before.contains(added)) {
                ensureTag(added, creatorId);
                tagMapper.incrementUsage(added);
            }
        }
    }

    /**
     * Decrements usage counts when a published post is removed or unpublished.
     *
     * @param tags tag names previously associated with the post
     */
    @Override
    @Transactional
    public void releasePublishedPostTags(List<String> tags) {
        for (String tag : normalize(tags)) {
            tagMapper.decrementUsage(tag);
        }
    }

    private void ensureTag(String name, long creatorId) {
        String slug = slugFor(name);
        tagMapper.upsert(name, slug, creatorId);
    }

    private String slugFor(String name) {
        String slug = SlugUtils.fromTitle(name);
        if (slug.isBlank()) {
            return name;
        }
        return slug;
    }

    private Set<String> normalize(List<String> tags) {
        Set<String> set = new HashSet<>();
        if (tags == null) {
            return set;
        }
        for (String tag : tags) {
            if (tag == null) {
                continue;
            }
            String trimmed = tag.trim();
            if (!trimmed.isEmpty() && trimmed.length() <= 64) {
                set.add(trimmed);
            }
        }
        return set;
    }

    private TagResponse toResponse(Tag tag) {
        return new TagResponse(
                String.valueOf(tag.getId()),
                tag.getName(),
                tag.getSlug(),
                tag.getUsageCount()
        );
    }
}
