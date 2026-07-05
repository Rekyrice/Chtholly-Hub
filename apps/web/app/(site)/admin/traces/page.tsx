"use client";

import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { traceService } from "@/lib/services/traceService";
import type {
  FailurePattern,
  TraceExecutionTrendRow,
  TraceStats,
  TraceSummary,
  TraceTokenTrendRow,
} from "@/lib/types/trace";
import { cn, formatDate } from "@/lib/utils";

type DateRange = {
  from: string;
  to: string;
  preset: "7d" | "30d" | "custom";
};

const CHART_COLORS = {
  sky: "#4ab0d9",
  emerald: "#10b981",
  violet: "#8b5cf6",
  sunset: "#e87461",
  amber: "#f59e0b",
  grid: "rgba(148, 163, 184, 0.22)",
};

function formatDateInput(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function quickRange(days: 7 | 30): DateRange {
  const to = new Date();
  const from = new Date();
  from.setDate(to.getDate() - days + 1);
  return {
    from: formatDateInput(from),
    to: formatDateInput(to),
    preset: days === 7 ? "7d" : "30d",
  };
}

function readInitialRange(): DateRange {
  if (typeof window === "undefined") return quickRange(7);
  const params = new URLSearchParams(window.location.search);
  const from = params.get("from");
  const to = params.get("to");
  if (from && to) {
    return { from, to, preset: "custom" };
  }
  return quickRange(7);
}

function updateRangeUrl(range: DateRange) {
  const url = new URL(window.location.href);
  url.searchParams.set("from", range.from);
  url.searchParams.set("to", range.to);
  window.history.replaceState(null, "", url.toString());
}

function formatMs(ms: number | null | undefined) {
  if (ms == null) return "-";
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function formatNumber(value: number | null | undefined) {
  return new Intl.NumberFormat("zh-CN").format(value ?? 0);
}

function formatDay(value: string) {
  return value.slice(5);
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

function makeEmptyExecutionTrend(range: DateRange): TraceExecutionTrendRow[] {
  const from = new Date(`${range.from}T00:00:00`);
  const to = new Date(`${range.to}T00:00:00`);
  const days = Math.max(1, Math.ceil((to.getTime() - from.getTime()) / 86_400_000) + 1);
  return Array.from({ length: days }, (_, index) => {
    const day = new Date(from);
    day.setDate(from.getDate() + index);
    return {
      day: formatDateInput(day),
      totalExecutions: 0,
      successCount: 0,
      successRate: 0,
    };
  });
}

function mergeExecutionTrend(range: DateRange, rows: TraceExecutionTrendRow[] = []) {
  const byDay = new Map(rows.map((row) => [row.day, row]));
  return makeEmptyExecutionTrend(range).map((row) => byDay.get(row.day) ?? row);
}

function mergeTokenTrend(range: DateRange, rows: TraceTokenTrendRow[] = []) {
  const byDay = new Map(rows.map((row) => [row.day, row]));
  return makeEmptyExecutionTrend(range).map((row) => ({
    day: row.day,
    inputTokens: byDay.get(row.day)?.inputTokens ?? 0,
    outputTokens: byDay.get(row.day)?.outputTokens ?? 0,
  }));
}

function ChartShell({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <section className="post-card p-5 min-h-[340px]">
      <h2 className="text-base font-semibold mb-4">{title}</h2>
      <div className="h-[260px]">{children}</div>
    </section>
  );
}

function EmptyChart() {
  return (
    <div className="h-full flex items-center justify-center text-sm text-text-secondary">
      暂无可视化数据
    </div>
  );
}

function StatCard({
  label,
  value,
  tone,
}: {
  label: string;
  value: string;
  tone?: "success" | "warn";
}) {
  return (
    <div className="post-card p-5">
      <div className="text-sm text-text-secondary">{label}</div>
      <div
        className={cn(
          "mt-2 text-2xl font-semibold",
          tone === "success" && "text-emerald-600",
          tone === "warn" && "text-amber-600",
        )}
      >
        {value}
      </div>
    </div>
  );
}

export default function TraceDashboardPage() {
  const [range, setRange] = useState<DateRange | null>(null);
  const [stats, setStats] = useState<TraceStats | null>(null);
  const [patterns, setPatterns] = useState<FailurePattern[]>([]);
  const [tokenTrends, setTokenTrends] = useState<TraceTokenTrendRow[]>([]);
  const [traces, setTraces] = useState<TraceSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const initial = readInitialRange();
    setRange(initial);
    updateRangeUrl(initial);
  }, []);

  const load = useCallback(async (nextRange: DateRange) => {
    setLoading(true);
    setError(null);
    try {
      const [statsResp, patternsResp, tokenResp, listResp] = await Promise.all([
        traceService.getTraceStats(nextRange.from, nextRange.to),
        traceService.getTracePatterns(nextRange.from, nextRange.to),
        traceService.getTokenTrends(nextRange.from, nextRange.to),
        traceService.list({ page: 0, size: 20 }),
      ]);
      setStats(statsResp);
      setPatterns(patternsResp);
      setTokenTrends(tokenResp);
      setTraces(listResp.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载 Trace 数据失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (range) void load(range);
  }, [load, range]);

  const executionTrend = useMemo(
    () => (range ? mergeExecutionTrend(range, stats?.executionTrend) : []),
    [range, stats?.executionTrend],
  );
  const tokenTrend = useMemo(
    () => (range ? mergeTokenTrend(range, tokenTrends.length ? tokenTrends : stats?.tokenTrend) : []),
    [range, stats?.tokenTrend, tokenTrends],
  );
  const patternBars = useMemo(
    () =>
      patterns.slice(0, 8).map((pattern) => ({
        name:
          pattern.patternKey.length > 22
            ? `${pattern.patternKey.slice(0, 22)}...`
            : pattern.patternKey,
        occurrenceCount: pattern.occurrenceCount,
      })),
    [patterns],
  );

  const applyRange = (nextRange: DateRange) => {
    setRange(nextRange);
    updateRangeUrl(nextRange);
  };

  const updateCustomRange = (field: "from" | "to", value: string) => {
    if (!range) return;
    applyRange({ ...range, [field]: value, preset: "custom" });
  };

  const tooltipStyle = {
    background: "var(--color-surface)",
    border: "1px solid var(--color-border)",
    borderRadius: 8,
    color: "var(--color-text)",
  };

  return (
    <div className="max-w-7xl mx-auto px-4 py-6">
      <div className="post-card p-6 mb-6">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <h1 className="entry-title mb-2">Trace Dashboard</h1>
            <p className="text-text-secondary text-sm">
              Agent 执行状态、耗时、token 消耗与失败模式趋势。
            </p>
          </div>

          {range && (
            <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
              <div className="inline-flex rounded-lg border border-border bg-surface p-1">
                <button
                  type="button"
                  className={cn(
                    "px-3 py-1.5 text-sm rounded-md transition",
                    range.preset === "7d" && "bg-sky text-white",
                  )}
                  onClick={() => applyRange(quickRange(7))}
                >
                  近 7 天
                </button>
                <button
                  type="button"
                  className={cn(
                    "px-3 py-1.5 text-sm rounded-md transition",
                    range.preset === "30d" && "bg-sky text-white",
                  )}
                  onClick={() => applyRange(quickRange(30))}
                >
                  近 30 天
                </button>
              </div>
              <div className="flex items-center gap-2 text-sm text-text-secondary">
                <input
                  type="date"
                  value={range.from}
                  onChange={(event) => updateCustomRange("from", event.target.value)}
                  className="rounded-md border border-border bg-surface px-2 py-1 text-text"
                />
                <span>至</span>
                <input
                  type="date"
                  value={range.to}
                  onChange={(event) => updateCustomRange("to", event.target.value)}
                  className="rounded-md border border-border bg-surface px-2 py-1 text-text"
                />
              </div>
            </div>
          )}
        </div>
      </div>

      {error && <div className="post-card p-4 mb-6 text-rose-600 text-sm">{error}</div>}

      {loading || !range ? (
        <div className="post-card p-8 text-center text-text-secondary">加载中...</div>
      ) : (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4 mb-6">
            <StatCard label="总执行次数" value={formatNumber(stats?.totalExecutions)} />
            <StatCard
              label="成功率"
              value={`${(stats?.successRate ?? 0).toFixed(1)}%`}
              tone="success"
            />
            <StatCard label="平均耗时" value={formatMs(stats?.avgDurationMs)} />
            <StatCard label="P95 耗时" value={formatMs(stats?.p95DurationMs)} tone="warn" />
          </div>

          <div className="grid grid-cols-1 gap-6 xl:grid-cols-2 mb-6">
            <ChartShell title="执行次数趋势">
              {executionTrend.some((row) => row.totalExecutions > 0) ? (
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={executionTrend} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                    <CartesianGrid stroke={CHART_COLORS.grid} vertical={false} />
                    <XAxis dataKey="day" tickFormatter={formatDay} stroke="var(--color-text-secondary)" />
                    <YAxis allowDecimals={false} stroke="var(--color-text-secondary)" />
                    <Tooltip contentStyle={tooltipStyle} labelFormatter={(value) => `日期：${value}`} />
                    <Line
                      type="monotone"
                      dataKey="totalExecutions"
                      name="执行次数"
                      stroke={CHART_COLORS.sky}
                      strokeWidth={2}
                      dot={false}
                    />
                  </LineChart>
                </ResponsiveContainer>
              ) : (
                <EmptyChart />
              )}
            </ChartShell>

            <ChartShell title="成功率趋势">
              {executionTrend.some((row) => row.totalExecutions > 0) ? (
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={executionTrend} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                    <CartesianGrid stroke={CHART_COLORS.grid} vertical={false} />
                    <XAxis dataKey="day" tickFormatter={formatDay} stroke="var(--color-text-secondary)" />
                    <YAxis
                      domain={[0, 100]}
                      tickFormatter={(value) => `${value}%`}
                      stroke="var(--color-text-secondary)"
                    />
                    <Tooltip
                      contentStyle={tooltipStyle}
                      formatter={(value) => [`${Number(value).toFixed(1)}%`, "成功率"]}
                      labelFormatter={(value) => `日期：${value}`}
                    />
                    <Line
                      type="monotone"
                      dataKey="successRate"
                      name="成功率"
                      stroke={CHART_COLORS.emerald}
                      strokeWidth={2}
                      dot={false}
                    />
                  </LineChart>
                </ResponsiveContainer>
              ) : (
                <EmptyChart />
              )}
            </ChartShell>

            <ChartShell title="Token 消耗趋势">
              {tokenTrend.some((row) => row.inputTokens + row.outputTokens > 0) ? (
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={tokenTrend} margin={{ top: 8, right: 16, left: 0, bottom: 0 }}>
                    <CartesianGrid stroke={CHART_COLORS.grid} vertical={false} />
                    <XAxis dataKey="day" tickFormatter={formatDay} stroke="var(--color-text-secondary)" />
                    <YAxis stroke="var(--color-text-secondary)" />
                    <Tooltip contentStyle={tooltipStyle} labelFormatter={(value) => `日期：${value}`} />
                    <Area
                      type="monotone"
                      dataKey="inputTokens"
                      name="Input tokens"
                      stackId="tokens"
                      stroke={CHART_COLORS.violet}
                      fill={CHART_COLORS.violet}
                      fillOpacity={0.35}
                    />
                    <Area
                      type="monotone"
                      dataKey="outputTokens"
                      name="Output tokens"
                      stackId="tokens"
                      stroke={CHART_COLORS.sunset}
                      fill={CHART_COLORS.sunset}
                      fillOpacity={0.35}
                    />
                  </AreaChart>
                </ResponsiveContainer>
              ) : (
                <EmptyChart />
              )}
            </ChartShell>

            <ChartShell title="失败模式排行">
              {patternBars.length > 0 ? (
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart
                    data={patternBars}
                    layout="vertical"
                    margin={{ top: 8, right: 16, left: 80, bottom: 0 }}
                  >
                    <CartesianGrid stroke={CHART_COLORS.grid} horizontal={false} />
                    <XAxis type="number" allowDecimals={false} stroke="var(--color-text-secondary)" />
                    <YAxis
                      type="category"
                      dataKey="name"
                      width={110}
                      tick={{ fontSize: 12 }}
                      stroke="var(--color-text-secondary)"
                    />
                    <Tooltip contentStyle={tooltipStyle} />
                    <Bar dataKey="occurrenceCount" name="出现次数" fill={CHART_COLORS.amber} radius={[0, 6, 6, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              ) : (
                <EmptyChart />
              )}
            </ChartShell>
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
                        {trace.correlationId.slice(0, 10)}...
                      </td>
                      <td className="py-2 pr-3">{trace.userId ?? "-"}</td>
                      <td className={cn("py-2 pr-3 font-medium", statusClass(trace.status))}>
                        {trace.status}
                      </td>
                      <td className="py-2 pr-3">{formatMs(trace.durationMs)}</td>
                      <td className="py-2 text-text-secondary">
                        {trace.startedAt ? formatDate(trace.startedAt) : "-"}
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
