package com.chtholly.agent.trace;

import lombok.Data;

import java.time.LocalDate;

@Data
public class TraceDailyTrendRow {

    private LocalDate day;

    private Long totalExecutions;

    private Long successCount;
}
