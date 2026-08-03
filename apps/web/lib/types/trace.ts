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
  compatibility: TraceCompatibility;
  timingAccuracy: TraceTimingAccuracy;
  phases: TracePhase[];
  metadata: TraceMetadata | null;
};

export type TraceCompatibility =
  | "NATIVE_V4"
  | "LEGACY_V3"
  | "UNSUPPORTED"
  | "MALFORMED";

export type TraceTimingAccuracy = "EXACT" | "DURATION_ONLY" | "NONE";

export type TracePhase = {
  phase: string;
  events: TraceEvent[];
};

export type TraceEvent = {
  id: string;
  sequence: number | null;
  stepIndex: number | null;
  phase: string;
  type: "llm" | "tool" | "lifecycle" | string;
  name: string;
  status: string;
  startedOffsetMs: number | null;
  durationMs: number | null;
  attempt: number | null;
  budgetBeforeMs: number | null;
  budgetAfterMs: number | null;
  errorCode: string | null;
  details: TraceEventDetails | null;
};

export type TraceLlmDetails = {
  purpose?: string | null;
  model?: string | null;
  inputChars?: number | null;
  outputChars?: number | null;
  firstTokenMs?: number | null;
  systemPrompt?: TraceContent | null;
  userPrompt?: TraceContent | null;
  rawOutput?: TraceContent | null;
  failureClass?: string | null;
  failureMessage?: TraceContent | null;
};

export type TraceToolDetails = {
  operation?: string | null;
  provider?: string | null;
  sourcePolicy?: string | null;
  sanitizedInput?: Record<string, unknown> | null;
  outputPreview?: string | null;
  outputSha256?: string | null;
  outputChars?: number | null;
  outputTruncated?: boolean | null;
  resultCount?: number | null;
  selectedIds?: string[] | null;
  inputSummary?: string | null;
  observationSummary?: string | null;
  rawInput?: TraceContent | null;
  rawObservation?: TraceContent | null;
  attributes?: Record<string, unknown> | null;
};

export type TraceLifecycleDetails = Partial<{
  model: string;
  runMode: string;
  skillId: string;
  skillVersion: string;
  sourceCount: number;
  evidenceCount: number;
  reason: string;
  toolCount: number;
  budgetMs: number;
  stage: string;
  terminalType: string;
  deliveryCode: string;
  modelFirstTokenMs: number;
  safeAnswerReadyMs: number;
  firstClientDeltaMs: number;
  answerChars: number;
  finalAnswer: TraceContent;
}>;

export type TraceEventDetails = TraceLlmDetails | TraceToolDetails | TraceLifecycleDetails;

export type TraceMetadata = {
  runMode?: string | null;
  failureType?: string | null;
  outcomeReason?: string | null;
  llmCallCount?: number | null;
  toolCallCount?: number | null;
  components?: Partial<Record<
    "prompt" | "skillSelector" | "model" | "retrieval" | "citationValidator" | "tools" | "traceSchema",
    string | null
  >> | null;
  skill?: {
    selectionStatus?: string | null;
    id?: string | null;
    version?: string | null;
    validationStatus?: string | null;
  } | null;
  retrieval?: {
    strategy?: string | null;
    statuses?: Record<string, string | null> | null;
    evidenceCount?: number | null;
    evidenceSnapshotHash?: string | null;
    degraded?: boolean | null;
    citationValidationStatus?: string | null;
    evidence?: Array<{
      citationId?: string | null;
      documentId?: string | null;
      source?: string | null;
      sourceVersion?: string | null;
      sourceHash?: string | null;
    }> | null;
  } | null;
  turn?: {
    requestId?: string | null;
    turnId?: string | null;
    chatSessionId?: string | null;
    connectionId?: string | null;
    budgetMs?: number | null;
    maxSteps?: number | null;
    timeoutStage?: string | null;
    cancelled?: boolean | null;
    clientDeliveryStatus?: string | null;
    clientTerminalType?: string | null;
    clientDeliveryCode?: string | null;
  } | null;
  memory?: {
    writeStatus?: string | null;
    failureCode?: string | null;
  } | null;
  toolPlan?: {
    reason?: string | null;
    effectiveTools?: string[] | null;
  } | null;
  steps?: Array<{
    stepIndex: number;
    action: string;
    llmMs: number;
    toolMs: number;
  }> | null;
  answerTiming?: {
    modelFirstTokenMs?: number | null;
    safeAnswerReadyMs?: number | null;
    firstClientDeltaMs?: number | null;
  } | null;
  capture?: {
    level?: string | null;
    policyVersion?: string | null;
    maxContentFieldChars?: number | null;
    maxTotalContentChars?: number | null;
    capturedContentChars?: number | null;
    truncatedContentFields?: number | null;
    credentialRedactions?: number | null;
  } | null;
  completeness?: {
    eventLimit?: number | null;
    droppedEvents?: number | null;
    truncatedToolOutputs?: number | null;
    complete?: boolean | null;
  } | null;
  input?: {
    fingerprint?: string | null;
    questionFingerprint?: string | null;
    pageContextFingerprint?: string | null;
    question?: TraceContent | null;
    pageContext?: TraceContent | null;
  } | null;
};

export type TraceContent = {
  text: string;
  sourceChars: number;
  sha256: string | null;
  truncated: boolean;
  credentialRedacted: boolean;
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
