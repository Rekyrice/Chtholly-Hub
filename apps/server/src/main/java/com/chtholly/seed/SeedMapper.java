package com.chtholly.seed;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper dedicated to cold-start seed data.
 */
@Mapper
public interface SeedMapper {

    boolean existsSeed(@Param("seedKey") String seedKey);

    void markSeed(@Param("seedKey") String seedKey, @Param("summaryJson") String summaryJson);

    int insertBangumiRecommendation(BangumiRecommendationSeed recommendation);

    int insertSeedUser(SeedUserRow user);

    int insertSeedPost(SeedPostRow post);

    int insertSeedComment(SeedCommentRow comment);

    int insertSeedInteractionComment(SeedInteractionCommentRow comment);

    int upsertFollowing(SeedFollowRow follow);

    int upsertFollower(SeedFollowRow follow);

    /** slug 冲突 upsert 后取库内真实 id，供 ES 索引使用。 */
    Long findPostIdBySlug(@Param("slug") String slug);

    /** 重复跑种子时复用已有账号 id，避免 posts.creator_id 外键失败。 */
    Long findUserIdByHandle(@Param("handle") String handle);
}
