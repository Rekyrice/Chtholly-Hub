package com.chtholly.post.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PostMapperStateTransitionContractTest {

    @Test
    void publishAndDeleteStatementsUseStateCompareAndSetGuards() throws IOException {
        String mapperXml = mapperXml();

        assertThat(statement(mapperXml, "publish")).contains("status = 'draft'");
        assertThat(statement(mapperXml, "updateContent")).contains("status = 'draft'");
        assertThat(statement(mapperXml, "softDelete")).contains("status != 'deleted'");
        assertThat(statement(mapperXml, "softDeleteById")).contains("status != 'deleted'");
    }

    @Test
    void aggregateMutationReadUsesARowLock() throws IOException {
        String mapperXml = mapperXml();

        assertThat(selectStatement(mapperXml, "findByIdForUpdate"))
                .contains("WHERE id = #{id}")
                .contains("FOR UPDATE");
    }

    @Test
    void bigVPullIncludesFollowerVisiblePosts() throws IOException {
        String mapperXml = mapperXml();

        assertThat(selectStatement(mapperXml, "listRecentPublicByCreators"))
                .contains("p.visible IN ('public', 'followers')");
    }

    @Test
    void followingFeedHydrationRequiresActiveRelationForEveryVisibility() throws IOException {
        String statement = selectStatement(mapperXml(), "listFollowingFeedRowsByIds")
                .replaceAll("\\s+", " ");

        assertThat(statement)
                .contains("p.visible IN ('public', 'followers')")
                .contains("FROM following f")
                .contains("f.from_user_id = #{viewerId}")
                .contains("f.to_user_id = p.creator_id")
                .contains("f.rel_status = 1")
                .doesNotContain("p.visible = 'public' OR");
    }

    @Test
    void detailCacheAuthorizationSnapshotAvoidsAuthorAndPayloadJoins() throws IOException {
        String statement = selectStatement(mapperXml(), "findDetailAudienceById");

        assertThat(statement)
                .contains("creator_id AS creatorId")
                .contains("status")
                .contains("visible")
                .contains("FROM posts")
                .contains("WHERE id = #{id}")
                .doesNotContain("JOIN users")
                .doesNotContain("content_url");
    }

    @Test
    void followingFeedFallbackUsesActiveMysqlRelationsAndStableOrder() throws IOException {
        String statement = selectStatement(mapperXml(), "listFollowingFeedAuthoritative");

        assertThat(statement)
                .contains("FROM following f")
                .contains("f.from_user_id = #{viewerId}")
                .contains("f.rel_status = 1")
                .contains("p.status = 'published'")
                .contains("p.visible IN ('public', 'followers')")
                .contains("ORDER BY p.publish_time DESC, p.id DESC")
                .contains("LIMIT #{limit} OFFSET #{offset}");
    }

    private static String mapperXml() throws IOException {
        try (InputStream input = PostMapperStateTransitionContractTest.class
                .getResourceAsStream("/mapper/PostMapper.xml")) {
            if (input == null) {
                throw new IllegalStateException("PostMapper.xml is missing from the test classpath");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String statement(String mapperXml, String id) {
        String startTag = "<update id=\"" + id + "\"";
        int start = mapperXml.indexOf(startTag);
        int end = mapperXml.indexOf("</update>", start);
        assertThat(start).as("mapper statement %s start", id).isGreaterThanOrEqualTo(0);
        assertThat(end).as("mapper statement %s end", id).isGreaterThan(start);
        return mapperXml.substring(start, end);
    }

    private static String selectStatement(String mapperXml, String id) {
        String startTag = "<select id=\"" + id + "\"";
        int start = mapperXml.indexOf(startTag);
        int end = mapperXml.indexOf("</select>", start);
        assertThat(start).as("mapper statement %s start", id).isGreaterThanOrEqualTo(0);
        assertThat(end).as("mapper statement %s end", id).isGreaterThan(start);
        return mapperXml.substring(start, end);
    }
}
