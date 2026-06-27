package com.chtholly.admin.api.dto;

public record AdminStatsResponse(
        long userCount,
        long postCount,
        long commentCount,
        long usersCreatedToday,
        long postsPublishedToday,
        long commentsCreatedToday
) {}
