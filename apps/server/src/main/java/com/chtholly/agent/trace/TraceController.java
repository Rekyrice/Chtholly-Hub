package com.chtholly.agent.trace;

import com.chtholly.admin.role.RequireRole;
import com.chtholly.admin.role.Role;
import com.chtholly.agent.trace.dto.FailurePatternDto;
import com.chtholly.agent.trace.dto.TraceDetailDto;
import com.chtholly.agent.trace.dto.TraceStatsDto;
import com.chtholly.agent.trace.dto.TraceSummaryDto;
import com.chtholly.common.api.pagination.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Agent 执行 trace 查询与统计（Admin）。 */
@RestController
@RequestMapping("/api/v1/traces")
@RequireRole(Role.ADMIN)
@RequiredArgsConstructor
public class TraceController {

    private final TraceQueryService traceQueryService;

    @GetMapping
    public PageResponse<TraceSummaryDto> listTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId) {
        return traceQueryService.listTraces(page, size, status, userId);
    }

    @GetMapping("/stats")
    public TraceStatsDto getStats(@RequestParam(defaultValue = "7") int days) {
        return traceQueryService.getStats(days);
    }

    @GetMapping("/patterns")
    public List<FailurePatternDto> getFailurePatterns() {
        return traceQueryService.getFailurePatterns();
    }

    @GetMapping("/{correlationId}")
    public TraceDetailDto getTrace(@PathVariable String correlationId) {
        return traceQueryService.getTrace(correlationId);
    }
}
