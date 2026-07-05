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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    public TraceStatsDto getStats(@RequestParam(defaultValue = "7") int days,
                                  @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to) {
        if (hasDateRange(from, to)) {
            DateRange range = parseDateRange(from, to);
            return traceQueryService.getStats(range.from(), range.to());
        }
        return traceQueryService.getStats(days);
    }

    @GetMapping("/patterns")
    public List<FailurePatternDto> getFailurePatterns(@RequestParam(required = false) String from,
                                                      @RequestParam(required = false) String to) {
        if (hasDateRange(from, to)) {
            DateRange range = parseDateRange(from, to);
            return traceQueryService.getFailurePatterns(range.from(), range.to());
        }
        return traceQueryService.getFailurePatterns();
    }

    @GetMapping("/token-trends")
    public List<TraceStatsDto.TokenTrendPoint> getTokenTrends(@RequestParam(required = false) String from,
                                                              @RequestParam(required = false) String to) {
        DateRange range = parseDateRange(from, to);
        return traceQueryService.getTokenTrends(range.from(), range.to());
    }

    @GetMapping("/{correlationId}")
    public TraceDetailDto getTrace(@PathVariable String correlationId) {
        return traceQueryService.getTrace(correlationId);
    }

    private boolean hasDateRange(String from, String to) {
        return hasText(from) || hasText(to);
    }

    private DateRange parseDateRange(String from, String to) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate toDate = hasText(to) ? LocalDate.parse(to) : LocalDate.now(zone);
        LocalDate fromDate = hasText(from) ? LocalDate.parse(from) : toDate.minusDays(7);
        // 前端传入的是日期粒度，后端用右开区间避免漏掉结束日期当天的数据。
        Instant fromInstant = fromDate.atStartOfDay(zone).toInstant();
        Instant toInstant = toDate.plusDays(1).atStartOfDay(zone).toInstant();
        return new DateRange(fromInstant, toInstant);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record DateRange(Instant from, Instant to) {}
}
