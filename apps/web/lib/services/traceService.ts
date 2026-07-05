import { apiFetch } from "./apiClient";
import type {
  FailurePattern,
  TraceDetail,
  TraceListResponse,
  TraceStats,
  TraceTokenTrendRow,
} from "@/lib/types/trace";

const TRACE_PREFIX = "/api/v1/traces";

function rangeQuery(from?: string, to?: string) {
  const search = new URLSearchParams();
  if (from) search.set("from", from);
  if (to) search.set("to", to);
  const query = search.toString();
  return query ? `?${query}` : "";
}

export const traceService = {
  list(params: { page?: number; size?: number; status?: string; userId?: number } = {}) {
    const search = new URLSearchParams();
    search.set("page", String(params.page ?? 0));
    search.set("size", String(params.size ?? 20));
    if (params.status) search.set("status", params.status);
    if (params.userId != null) search.set("userId", String(params.userId));
    return apiFetch<TraceListResponse>(`${TRACE_PREFIX}?${search.toString()}`);
  },

  detail(correlationId: string) {
    return apiFetch<TraceDetail>(`${TRACE_PREFIX}/${encodeURIComponent(correlationId)}`);
  },

  stats(days = 7) {
    return apiFetch<TraceStats>(`${TRACE_PREFIX}/stats?days=${days}`);
  },

  patterns() {
    return apiFetch<FailurePattern[]>(`${TRACE_PREFIX}/patterns`);
  },

  getTraceStats(from?: string, to?: string) {
    return apiFetch<TraceStats>(`${TRACE_PREFIX}/stats${rangeQuery(from, to)}`);
  },

  getTracePatterns(from?: string, to?: string) {
    return apiFetch<FailurePattern[]>(`${TRACE_PREFIX}/patterns${rangeQuery(from, to)}`);
  },

  getTokenTrends(from?: string, to?: string) {
    return apiFetch<TraceTokenTrendRow[]>(`${TRACE_PREFIX}/token-trends${rangeQuery(from, to)}`);
  },
};
