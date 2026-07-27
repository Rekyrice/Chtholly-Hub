package com.chtholly.bangumi.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BangumiSubjectMapperContractTest {

    @Test
    void keywordQueriesOrderExactAndPrefixMatchesBeforeScore() throws Exception {
        try (var input = getClass().getResourceAsStream("/mapper/BangumiSubjectMapper.xml")) {
            assertThat(input).isNotNull();
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(xml)
                    .contains("WHEN name = #{keyword} OR name_cn = #{keyword} THEN 0")
                    .contains("WHEN name LIKE CONCAT(#{keyword}, '%')")
                    .contains("score DESC, `rank` ASC");
        }
    }
}
