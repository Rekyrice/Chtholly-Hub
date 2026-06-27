package com.chtholly.admin.mapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminStatsMapper {
    long countUsers();

    long countPublishedPosts();

    long countComments();

    long countUsersCreatedToday();

    long countPostsPublishedToday();

    long countCommentsCreatedToday();
}
