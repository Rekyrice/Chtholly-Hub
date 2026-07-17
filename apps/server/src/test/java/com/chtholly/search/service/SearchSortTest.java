package com.chtholly.search.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSortTest {

    @Test
    void fromDefaultsToRelevanceForMissingOrInvalidValues() {
        assertThat(SearchSort.from(null)).isEqualTo(SearchSort.RELEVANCE);
        assertThat(SearchSort.from("")).isEqualTo(SearchSort.RELEVANCE);
        assertThat(SearchSort.from("   ")).isEqualTo(SearchSort.RELEVANCE);
        assertThat(SearchSort.from("popular")).isEqualTo(SearchSort.RELEVANCE);
    }

    @Test
    void fromNormalizesKnownValues() {
        assertThat(SearchSort.from(" relevance ")).isEqualTo(SearchSort.RELEVANCE);
        assertThat(SearchSort.from(" NeWeSt ")).isEqualTo(SearchSort.NEWEST);
    }
}
