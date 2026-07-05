export type TraceStatus = "SUCCESS" | "FAILURE" | "TIMEOUT" | "ABORTED";
export type TraceSummary = {
  correlationId: string;
  userId: number | null;
  sessionId: string | null;
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  status: TraceStatus;
  stepsCount: number;
  inputTokens: number;
  outputTokens: number;
  errorMessage: string | null;
};

export type TraceDetail = {
  correlationId: string;
  userId: number | null;
  sessionId: string | null;
  status: TraceStatus;
  durationMs: number | null;
  stepsCount: number;
  errorMessage: string | null;
  toolCalls: unknown;
  tracePayload: unknown;
};

export type FailurePattern = {
  patternKey: string;
  occurrenceCount: number;
  lastSeenAt: string;
  sampleTraceIds: string[];
  resolutionHint: string | null;
};

export type TraceTokenTrendRow = {
  day: string;
  inputTokens: number;
  outputTokens: number;
};

export type TraceExecutionTrendRow = {
  day: string;
  totalExecutions: number;
  successCount: number;
  successRate: number;
};

export type TraceStats = {
  days: number;
  totalExecutions: number;
  successCount: number;
  failureCount: number;
  timeoutCount: number;
  abortedCount: number;
  successRate: number;
  avgDurationMs: number | null;
  p95DurationMs: number | null;
  topFailurePatterns: FailurePattern[];
  tokenTrend: TraceTokenTrendRow[];
  executionTrend: TraceExecutionTrendRow[];
};

export type TraceListResponse = {
  items: TraceSummary[];
  page: number;
  size: number;
  total: number;
  hasMore: boolean;
};
