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

@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagMapper tagMapper;

    @Override
    @Transactional(readOnly = true)
    public List<TagResponse> listTags(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return tagMapper.listOrderByUsage(safeLimit).stream()
                .map(this::toResponse)
                .toList();
    }

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
