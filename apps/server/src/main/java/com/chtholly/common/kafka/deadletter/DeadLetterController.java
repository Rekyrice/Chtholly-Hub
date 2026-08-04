package com.chtholly.common.kafka.deadletter;

import com.chtholly.admin.role.RequireRole;
import com.chtholly.admin.role.Role;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exposes administrator HTTP mappings for dead-letter queries and replay commands. */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
@RequireRole(Role.ADMIN)
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
public class DeadLetterController {

    private final DeadLetterMessageService deadLetterMessageService;
    private final DeadLetterReplayService deadLetterReplayService;

    /**
     * Creates the administrator dead-letter endpoint adapter.
     *
     * @param deadLetterMessageService dead-letter query service
     * @param deadLetterReplayService replay lifecycle service
     */
    public DeadLetterController(
            DeadLetterMessageService deadLetterMessageService,
            DeadLetterReplayService deadLetterReplayService) {
        this.deadLetterMessageService = deadLetterMessageService;
        this.deadLetterReplayService = deadLetterReplayService;
    }

    /**
     * Lists dead-letter rows using the requested filters and pagination.
     *
     * @param topic optional source topic filter
     * @param status optional status filter
     * @param page one-based page number
     * @param size requested page size
     * @return the mapped dead-letter page
     */
    @GetMapping
    public DeadLetterPageResponse list(
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<DeadLetterResponse> items =
                deadLetterMessageService.listResults(topic, status, page, size)
                        .stream()
                        .map(DeadLetterResponse::from)
                        .toList();
        long total = deadLetterMessageService.count(topic, status);
        return new DeadLetterPageResponse(items, total, page, size);
    }

    /**
     * Replays one dead-letter row.
     *
     * @param id dead-letter row identifier
     * @return the row after a confirmed replay
     */
    @PostMapping("/{id}/replay")
    public DeadLetterResponse replay(@PathVariable long id) {
        return DeadLetterResponse.from(deadLetterReplayService.replay(id));
    }

    /**
     * Recovers one expired replay generation into manual uncertainty.
     *
     * @param id dead-letter row identifier
     * @param attemptToken replay generation token
     * @return the recovered row
     */
    @PostMapping("/{id}/recover-expired")
    public DeadLetterResponse recoverExpired(
            @PathVariable long id,
            @RequestParam String attemptToken) {
        return DeadLetterResponse.from(
                deadLetterReplayService.recoverExpired(id, attemptToken));
    }

    /**
     * Resolves one manually verified uncertain replay generation.
     *
     * @param id dead-letter row identifier
     * @param attemptToken replay generation token
     * @param published whether publication was verified
     * @return the resolved row
     */
    @PostMapping("/{id}/resolve")
    public DeadLetterResponse resolve(
            @PathVariable long id,
            @RequestParam String attemptToken,
            @RequestParam boolean published) {
        return DeadLetterResponse.from(
                deadLetterReplayService.resolve(id, attemptToken, published));
    }
}
