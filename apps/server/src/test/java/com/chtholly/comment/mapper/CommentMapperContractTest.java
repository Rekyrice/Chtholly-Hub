package com.chtholly.comment.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommentMapperContractTest {

    @Test
    void batchActiveCountIncludesRepliesAndExcludesSoftDeletedRows() throws Exception {
        String xml = Files.readString(
                Path.of("src/main/resources/mapper/CommentMapper.xml"),
                StandardCharsets.UTF_8);

        assertThat(xml).contains("<select id=\"countActiveByPostIds\"");
        String statement = xml.substring(
                xml.indexOf("<select id=\"countActiveByPostIds\""),
                xml.indexOf("</select>", xml.indexOf("<select id=\"countActiveByPostIds\"")));

        assertThat(statement)
                .contains("deleted_at IS NULL")
                .contains("GROUP BY post_id")
                .contains("collection=\"postIds\"")
                .doesNotContain("parent_id IS NULL");
    }
}
