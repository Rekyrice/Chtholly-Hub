package com.chtholly.admin.service;

import com.chtholly.admin.api.dto.AdminStatsResponse;
import com.chtholly.admin.mapper.AdminStatsMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminStatsService {

    private final AdminStatsMapper statsMapper;

    public AdminStatsResponse getStats() {
        return new AdminStatsResponse(
                statsMapper.countUsers(),
                statsMapper.countPublishedPosts(),
                statsMapper.countComments(),
                statsMapper.countUsersCreatedToday(),
                statsMapper.countPostsPublishedToday(),
                statsMapper.countCommentsCreatedToday()
        );
    }
}
