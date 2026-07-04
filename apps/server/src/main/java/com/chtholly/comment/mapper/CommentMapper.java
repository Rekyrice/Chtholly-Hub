package com.chtholly.comment.mapper;

import com.chtholly.comment.model.CommentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CommentMapper {

    /** 分页查询顶层评论（未软删除）。 */
    List<CommentRow> listRootByPostId(@Param("postId") long postId,
                                      @Param("limit") int limit,
                                      @Param("offset") int offset);

    /** 查询指定父评论下的全部子评论（含已软删除，用于保留树结构）。 */
    List<CommentRow> listRepliesByParentIds(@Param("postId") long postId,
                                            @Param("parentIds") List<Long> parentIds);

    List<CommentRow> findByIds(@Param("ids") List<Long> ids);

    CommentRow findById(@Param("id") long id);

    int insert(@Param("id") long id,
               @Param("postId") long postId,
               @Param("parentId") Long parentId,
               @Param("userId") long userId,
               @Param("content") String content,
               @Param("isChtholly") boolean isChtholly);

    /** 今日珂朵莉评论数（UTC 日界）。 */
    long countChthollyCommentsSince(@Param("since") java.time.Instant since);

    /** 近期公开帖中尚无珂朵莉评论的候选。 */
    List<com.chtholly.post.model.PostFeedRow> listRecentPublicWithoutChthollyComment(
            @Param("since") java.time.Instant since,
            @Param("limit") int limit);

    /** 顶层评论总数（未软删除，用于分页）。 */
    long countRootByPostId(@Param("postId") long postId);

    /** 评论作者软删除。 */
    int softDelete(@Param("id") long id, @Param("userId") long userId);

    /** 文章作者等特权软删除（不校验 user_id）。 */
    int softDeleteById(@Param("id") long id);
}
