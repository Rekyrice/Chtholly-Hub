package com.chtholly.tag.service.impl;

import com.chtholly.tag.mapper.TagMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TagServiceImplTest {

    @Mock
    private TagMapper tagMapper;

    private TagServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TagServiceImpl(tagMapper);
    }

    @Test
    void releasePublishedPostTags_decrementsEachTag() {
        service.releasePublishedPostTags(List.of("java", "spring"));

        verify(tagMapper).decrementUsage("java");
        verify(tagMapper).decrementUsage("spring");
    }

    /** usage_count 已为 0 时仍调用 decrement，由 SQL GREATEST 兜底，不会 UNSIGNED 下溢。 */
    @Test
    void releasePublishedPostTags_decrementsEvenWhenUsageCountIsZero() {
        service.releasePublishedPostTags(List.of("orphan"));

        verify(tagMapper).decrementUsage("orphan");
    }

    @Test
    void syncPublishedPostTags_incrementsAddedAndDecrementsRemoved() {
        service.syncPublishedPostTags(1L, List.of("a", "b"), List.of("b", "c"));

        verify(tagMapper).decrementUsage("a");
        verify(tagMapper, never()).decrementUsage("b");
        verify(tagMapper).upsert("c", "c", 1L);
        verify(tagMapper).incrementUsage("c");
    }

    @Test
    void decrementUsageSqlUsesGreatestToPreventUnderflow() throws Exception {
        Path xml = Path.of("src/main/resources/mapper/TagMapper.xml");
        String content = Files.readString(xml, StandardCharsets.UTF_8);

        assertThat(content).contains("GREATEST(usage_count - 1, 0)");
        assertThat(content).doesNotContain("usage_count = usage_count - 1");
    }
}
