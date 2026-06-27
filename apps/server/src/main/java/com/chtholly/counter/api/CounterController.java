package com.chtholly.counter.api;

import com.chtholly.counter.api.dto.CountsResponse;
import com.chtholly.counter.schema.CounterSchema;
import com.chtholly.counter.service.CounterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Read-only REST API for entity metric counts stored in Redis SDS.
 */
@RestController
@RequestMapping("/api/v1/counter")
public class CounterController {

    private final CounterService counterService;

    public CounterController(CounterService counterService) {
        this.counterService = counterService;
    }

    /**
     * Returns aggregated counts for an entity and selected metrics.
     *
     * @param entityType entity type (e.g. {@code post})
     * @param entityId entity snowflake ID as string
     * @param metricsStr optional comma-separated metric names; all supported metrics when blank
     * @return count map wrapped in a response DTO
     */
    @GetMapping("/{etype}/{eid}")
    public ResponseEntity<CountsResponse> getCounts(@PathVariable("etype") String entityType,
                                                    @PathVariable("eid") String entityId,
                                                    @RequestParam(value = "metrics", required = false) String metricsStr) {
        List<String> metrics;
        if (metricsStr == null || metricsStr.isBlank()) {
            metrics = new ArrayList<>(CounterSchema.SUPPORTED_METRICS); // 未指定指标时返回全部支持的计数
        } else {
            metrics = Arrays.stream(metricsStr.split(","))
                    .map(String::trim)
                    .filter(CounterSchema.SUPPORTED_METRICS::contains) // 过滤未知指标，保证请求安全
                    .toList();
        }

        Map<String, Long> counts = counterService.getCounts(entityType, entityId, metrics);

        return ResponseEntity.ok(new CountsResponse(entityType, entityId, counts));
    }
}