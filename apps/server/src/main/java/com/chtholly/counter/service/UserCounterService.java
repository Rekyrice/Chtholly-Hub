package com.chtholly.counter.service;

/**
 * 用户维度计数服务接口。
 *
 * <p>支持维护关注数、粉丝数、发文数、获赞数、获收藏数，并提供全量重建。</p>
 */
public interface UserCounterService {
    /** 增量更新关注数 */
    void incrementFollowings(long userId, int delta);
    /** 增量更新粉丝数 */
    void incrementFollowers(long userId, int delta);
    /** 增量更新发文数 */
    void incrementPosts(long userId, int delta);
    /** 幂等失效用户计数缓存，防止乱序互动事件永久累加。 */
    void invalidateReactionCounters(long userId);
    /** 从 MySQL 互动关系事实读取作者收到的点赞数。 */
    long countLikesReceived(long userId);
    /** 从 MySQL 互动关系事实读取作者收到的收藏数。 */
    long countFavsReceived(long userId);
    /** 基于事实重建全部计数 */
    void rebuildAllCounters(long userId);
}
