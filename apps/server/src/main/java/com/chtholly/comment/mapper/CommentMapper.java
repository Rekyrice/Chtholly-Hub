package com.chtholly.comment.mapper;

import com.chtholly.comment.model.CommentRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CommentMapper {

    List<CommentRow> listByPostId(@Param("postId") long postId);

    CommentRow findById(@Param("id") long id);

    int insert(@Param("id") long id,
               @Param("postId") long postId,
               @Param("parentId") Long parentId,
               @Param("userId") long userId,
               @Param("content") String content);

    long countByPostId(@Param("postId") long postId);
}
