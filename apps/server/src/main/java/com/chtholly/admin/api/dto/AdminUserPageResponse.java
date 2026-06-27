package com.chtholly.admin.api.dto;

import java.util.List;

public record AdminUserPageResponse(
        List<AdminUserResponse> items,
        int page,
        int size,
        boolean hasMore,
        long total
) {}
