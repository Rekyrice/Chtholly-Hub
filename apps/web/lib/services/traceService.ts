import { apiFetch } from "./apiClient";
import type {
  FailurePattern,
  TraceDetail,
  TraceListResponse,
  TraceStats,
} from "@/lib/types/trace";

const TRACE_PREFIX = "/api/v1/traces";

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
};
