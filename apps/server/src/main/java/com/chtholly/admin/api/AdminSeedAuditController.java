package com.chtholly.admin.api;

import com.chtholly.admin.role.RequireRole;
import com.chtholly.admin.role.Role;
import com.chtholly.seed.SeedAuditResultResponse;
import com.chtholly.seed.SeedContentAuditor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin endpoints for Chtholly seed content audit results. */
@RestController
@RequestMapping({"/api/admin/seed", "/api/v1/admin/seed"})
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
public class AdminSeedAuditController {

    private final SeedContentAuditor seedContentAuditor;

    /**
     * Returns seed posts marked as needing manual review.
     *
     * @return audit result list.
     */
    @GetMapping("/audit-results")
    public List<SeedAuditResultResponse> auditResults() {
        return seedContentAuditor.listNeedsReviewResults();
    }
}
