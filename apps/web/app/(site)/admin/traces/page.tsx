"use client";

import { useCallback, useEffect, useState } from "react";
import { traceService } from "@/lib/services/traceService";
import type { FailurePattern, TraceStats, TraceSummary } from "@/lib/types/trace";
import { cn, formatDate } from "@/lib/utils";

function formatMs(ms: number | null | undefined) {
  if (ms == null) return "—";
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function statusClass(status: string) {
  switch (status) {
    case "SUCCESS":
      return "text-emerald-600";
    case "TIMEOUT":
      return "text-amber-600";
    case "ABORTED":
      return "text-orange-600";
    default:
      return "text-rose-600";
  }
}

export default function TraceDashboardPage() {
  const [stats, setStats] = useState<TraceStats | null>(null);
  const [patterns, setPatterns] = useState<FailurePattern[]>([]);
  const [traces, setTraces] = useState<TraceSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [statsResp, patternsResp, listResp] = await Promise.all([
        traceService.stats(7),
        traceService.patterns(),
        traceService.list({ page: 0, size: 20 }),
      ]);
      setStats(statsResp);
      setPatterns(patternsResp);
      setTraces(listResp.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载 Trace 数据失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="max-w-6xl mx-auto px-4 py-6">
      <div className="post-card p-6 mb-6">
        <h1 className="entry-title mb-2">Trace Dashboard</h1>
        <p className="text-text-secondary text-sm">Agent 执行 trace 统计与失败模式（Admin）</p>
      </div>

      {error && (
        <div className="post-card p-4 mb-6 text-rose-600 text-sm">{error}</div>
      )}

      {loading ? (
        <div className="post-card p-8 text-center text-text-secondary">加载中…</div>
      ) : (
        <>
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-6 mb-6">
            <section className="post-card p-5">
              <h2 className="text-lg font-semibold mb-4">Stats（近 {stats?.days ?? 7} 天）</h2>
              {stats ? (
                <dl className="grid grid-cols-2 gap-3 text-sm">
                  <div>
                    <dt className="text-text-secondary">Total</dt>
                    <dd className="text-xl font-semibold">{stats.totalExecutions}</dd>
                  </div>
                  <div>
                    <dt className="text-text-secondary">Success</dt>
                    <dd className="text-xl font-semibold text-emerald-600">
                      {stats.successRate.toFixed(1)}%
                    </dd>
                  </div>
                  <div>
                    <dt className="text-text-secondary">Avg</dt>
                    <dd className="font-medium">{formatMs(stats.avgDurationMs)}</dd>
                  </div>
                  <div>
                    <dt className="text-text-secondary">P95</dt>
                    <dd className="font-medium">{formatMs(stats.p95DurationMs)}</dd>
                  </div>
                  <div>
                    <dt className="text-text-secondary">Failure</dt>
                    <dd>{stats.failureCount}</dd>
                  </div>
                  <div>
                    <dt className="text-text-secondary">Timeout</dt>
                    <dd>{stats.timeoutCount}</dd>
                  </div>
                </dl>
              ) : (
                <p className="text-text-secondary text-sm">暂无统计数据</p>
              )}
            </section>

            <section className="post-card p-5">
              <h2 className="text-lg font-semibold mb-4">Failure Patterns</h2>
              {patterns.length === 0 ? (
                <p className="text-text-secondary text-sm">暂无失败模式</p>
              ) : (
                <ol className="list-decimal pl-5 space-y-3 text-sm">
                  {patterns.slice(0, 8).map((pattern) => (
                    <li key={pattern.patternKey}>
                      <div className="font-medium">{pattern.patternKey}</div>
                      <div className="text-text-secondary">
                        {pattern.occurrenceCount} occurrences
                      </div>
                      {pattern.resolutionHint && (
                        <div className="text-text-secondary mt-1">{pattern.resolutionHint}</div>
                      )}
                    </li>
                  ))}
                </ol>
              )}
            </section>
          </div>

          <section className="post-card p-5 overflow-x-auto">
            <h2 className="text-lg font-semibold mb-4">Recent Traces</h2>
            <table className="w-full text-sm border-collapse min-w-[640px]">
              <thead>
                <tr className="border-b border-border text-left text-text-secondary">
                  <th className="py-2 pr-3">corrId</th>
                  <th className="py-2 pr-3">user</th>
                  <th className="py-2 pr-3">status</th>
                  <th className="py-2 pr-3">dur</th>
                  <th className="py-2">time</th>
                </tr>
              </thead>
              <tbody>
                {traces.length === 0 ? (
                  <tr>
                    <td colSpan={5} className="py-6 text-center text-text-secondary">
                      暂无 trace 记录
                    </td>
                  </tr>
                ) : (
                  traces.map((trace) => (
                    <tr key={trace.correlationId} className="border-b border-border/60">
                      <td className="py-2 pr-3 font-mono text-xs">
                        {trace.correlationId.slice(0, 10)}…
                      </td>
                      <td className="py-2 pr-3">{trace.userId ?? "—"}</td>
                      <td className={cn("py-2 pr-3 font-medium", statusClass(trace.status))}>
                        {trace.status}
                      </td>
                      <td className="py-2 pr-3">{formatMs(trace.durationMs)}</td>
                      <td className="py-2 text-text-secondary">
                        {trace.startedAt ? formatDate(trace.startedAt) : "—"}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </section>
        </>
      )}
    </div>
  );
}
