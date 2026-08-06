package com.chtholly.comment.mapper;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommentMapperContractTest {

    private static final Path COMMENT_MAPPER_XML =
            Path.of("src/main/resources/mapper/CommentMapper.xml");

    private static final String ACTIVITY_INDEX_DEFINITION =
            "KEY ix_comments_user_deleted_ct (user_id, deleted_at, created_at, id)";

    @Test
    void batchActiveCountIncludesRepliesAndExcludesSoftDeletedRows() throws Exception {
        String xml = Files.readString(COMMENT_MAPPER_XML, StandardCharsets.UTF_8);

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

    @Test
    void publicUserActivityUsesPublishedPublicPostVisibilityAndStablePagination() throws Exception {
        String xml = Files.readString(COMMENT_MAPPER_XML, StandardCharsets.UTF_8);

        String statement = selectStatement(xml, "listPublicActivityByUserId");

        assertThat(statement)
                .contains("FROM comments c")
                .contains("JOIN posts p ON p.id = c.post_id")
                .contains("c.user_id = #{userId}")
                .contains("c.deleted_at IS NULL")
                .contains("p.status = 'published'")
                .contains("p.visible = 'public'")
                .contains("ORDER BY c.created_at DESC, c.id DESC")
                .contains("LIMIT #{limit} OFFSET #{offset}");
    }

    @Test
    void publicUserActivityCountUsesTheSameVisibilityFilters() throws Exception {
        String xml = Files.readString(COMMENT_MAPPER_XML, StandardCharsets.UTF_8);

        String statement = selectStatement(xml, "countPublicActivityByUserId");

        assertThat(statement)
                .contains("FROM comments c")
                .contains("JOIN posts p ON p.id = c.post_id")
                .contains("c.user_id = #{userId}")
                .contains("c.deleted_at IS NULL")
                .contains("p.status = 'published'")
                .contains("p.visible = 'public'");
    }

    @Test
    void activityIndexExistsInMigrationAndFullSchema() throws Exception {
        Path migration = Path.of("db/migration/V30__user_comment_activity_index.sql");
        assertThat(migration).exists();

        String migrationSql = Files.readString(migration, StandardCharsets.UTF_8);
        String schemaSql = Files.readString(Path.of("db/schema.sql"), StandardCharsets.UTF_8);

        assertThat(migrationSql)
                .contains("FROM information_schema.statistics")
                .contains("table_schema = DATABASE()")
                .contains("table_name = 'comments'")
                .contains("index_name = 'ix_comments_user_deleted_ct'")
                .contains("COUNT(*) = 4")
                .contains("GROUP_CONCAT(column_name ORDER BY seq_in_index)")
                .contains("'user_id,deleted_at,created_at,id'")
                .contains("SUM(sub_part IS NOT NULL) = 0")
                .contains("MIN(is_visible) = 'YES'")
                .contains("MIN(index_type) = 'BTREE'")
                .contains("MIN(non_unique) = 1")
                .contains("IF(")
                .contains("ALTER TABLE comments DROP INDEX ix_comments_user_deleted_ct, ADD "
                        + ACTIVITY_INDEX_DEFINITION)
                .contains("ADD " + ACTIVITY_INDEX_DEFINITION)
                .contains("PREPARE")
                .contains("EXECUTE")
                .contains("DEALLOCATE PREPARE");
        assertThat(schemaSql).contains(ACTIVITY_INDEX_DEFINITION);
    }

    private String selectStatement(String xml, String id) {
        String openingTag = "<select id=\"" + id + "\"";
        assertThat(xml).contains(openingTag);
        int start = xml.indexOf(openingTag);
        return xml.substring(start, xml.indexOf("</select>", start));
    }
}
