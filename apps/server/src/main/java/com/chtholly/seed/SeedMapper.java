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

    int upsertFollowing(SeedFollowRow follow);

    int upsertFollower(SeedFollowRow follow);
}
