package com.chtholly.agent.trace;

import lombok.Data;

import java.time.LocalDate;

/** 按日 token 用量聚合行。 */
@Data
public class TraceTokenTrendRow {
    private LocalDate day;
    private Long inputTokens;
    private Long outputTokens;
}
