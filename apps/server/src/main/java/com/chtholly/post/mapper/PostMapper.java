package com.chtholly.post.mapper;

import com.chtholly.post.model.Post;
import com.chtholly.post.model.PostDetailEtagRow;
import com.chtholly.post.model.PostDetailRow;

import com.chtholly.post.model.PostFeedRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface PostMapper {
    void insertDraft(Post post);

    Post findById(@Param("id") Long id);

    List<Post> findByIds(@Param("ids") List<Long> ids);

    int updateContent(Post post);

    int updateMetadata(Post post);

    int publish(@Param("id") Long id, @Param("creatorId") Long creatorId);

    // 首页 Feed 列表（已发布、公开可见），按发布时间倒序 + offset 分页。
    List<PostFeedRow> listFeedPublic(@Param("limit") int limit,
                                         @Param("offset") int offset);

    /** 游标分页：返回 (publish_time, id) 严格小于锚点的记录。 */
    List<PostFeedRow> listFeedPublicByCursor(@Param("cursorTime") Instant cursorTime,
                                             @Param("cursorId") long cursorId,
                                             @Param("limit") int limit);

    List<PostFeedRow> listFeedPublicByCreator(@Param("creatorId") long creatorId,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    List<PostFeedRow> listFeedPublicByTag(@Param("tagName") String tagName,
                                          @Param("creatorId") Long creatorId,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    // 我的帖子列表（当前用户已发布内容），置顶优先，其次按发布时间倒序。
    List<PostFeedRow> listMyPublished(@Param("creatorId") long creatorId,
                                                                              @Param("limit") int limit,
                                                                              @Param("offset") int offset);

    // 设置置顶
    int updateTop(@Param("id") Long id, @Param("creatorId") Long creatorId, @Param("isTop") Boolean isTop);

    // 设置可见性
    int updateVisibility(@Param("id") Long id, @Param("creatorId") Long creatorId, @Param("visible") String visible);

    // 软删除
    int softDelete(@Param("id") Long id, @Param("creatorId") Long creatorId);

    int updateVisibilityById(@Param("id") Long id, @Param("visible") String visible);

    int softDeleteById(@Param("id") Long id);

    // 详情查询（含作者信息）
    PostDetailRow findDetailById(@Param("id") Long id);

    PostDetailRow findDetailBySlug(@Param("slug") String slug);

    PostDetailEtagRow findDetailEtagById(@Param("id") long id);

    PostDetailEtagRow findDetailEtagBySlug(@Param("slug") String slug);

    Long findIdBySlug(@Param("slug") String slug);

    int updateSlug(@Param("id") Long id, @Param("creatorId") Long creatorId, @Param("slug") String slug);

    // 统计我的已发布帖子数量
    long countMyPublished(@Param("creatorId") long creatorId);

    /** 公开个人主页：已发布且 public 可见的帖子数 */
    long countPublicPublishedByCreator(@Param("creatorId") long creatorId);

    // 列出我的已发布帖子ID列表
    List<Long> listMyPublishedIds(@Param("creatorId") long creatorId);

    /** 按 ID 批量查询 Feed 行（仅已发布且 public/followers 可见）。 */
    List<PostFeedRow> listFeedRowsByIds(@Param("ids") List<Long> ids);

    /** 创作者在指定时间之后发布的公开/粉丝可见帖子 ID（用于 timeline 清理）。 */
    List<Long> listPublishedIdsByCreatorSince(@Param("creatorId") long creatorId,
                                              @Param("since") Instant since,
                                              @Param("limit") int limit);

    /** 多个创作者在指定时间之后的近期公开帖子（拉模式大 V）。 */
    List<PostFeedRow> listRecentPublicByCreators(@Param("creatorIds") List<Long> creatorIds,
                                                @Param("since") Instant since,
                                                @Param("limit") int limit);

    /** Recent public posts after a timestamp for background cognitive jobs. */
    List<PostFeedRow> listRecentPublicSince(@Param("since") Instant since,
                                            @Param("limit") int limit);
}
