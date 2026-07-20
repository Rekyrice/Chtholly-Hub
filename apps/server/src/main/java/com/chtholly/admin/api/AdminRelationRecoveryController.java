package com.chtholly.admin.api;

import com.chtholly.admin.role.RequireRole;
import com.chtholly.admin.role.Role;
import com.chtholly.relation.outbox.RelationOutboxReplayService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Administrator-only relation projection recovery operations. */
@RestController
@RequestMapping("/api/v1/admin/relations/outbox")
@RequireRole(Role.ADMIN)
public class AdminRelationRecoveryController {

    private final RelationOutboxReplayService replayService;

    public AdminRelationRecoveryController(RelationOutboxReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/{outboxId}/replay")
    public Map<String, Integer> replayId(@PathVariable long outboxId) {
        return Map.of("processed", replayService.replayId(outboxId));
    }

    @PostMapping("/replay")
    public Map<String, Integer> replayRange(@RequestParam long fromId, @RequestParam long toId) {
        return Map.of("processed", replayService.replayRange(fromId, toId));
    }
}
