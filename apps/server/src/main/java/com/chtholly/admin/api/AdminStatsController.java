package com.chtholly.admin.api;

import com.chtholly.admin.api.dto.AdminStatsResponse;
import com.chtholly.admin.role.RequireRole;
import com.chtholly.admin.role.Role;
import com.chtholly.admin.service.AdminStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin 系统统计接口。 */
@RestController
@RequestMapping("/api/v1/admin/stats")
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    @GetMapping
    public AdminStatsResponse stats() {
        return adminStatsService.getStats();
    }
}
