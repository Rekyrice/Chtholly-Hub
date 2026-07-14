"use client";

import Link from "next/link";
import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from "react";
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
  TraceListResponse,
  TraceStats,
  TraceStatus,
  TraceTokenTrendRow,
} from "@/lib/types/trace";
import { cn, formatDate } from "@/lib/utils";

type DateRange = {
  from: string;
  to: string;
  preset: "7d" | "30d" | "custom";
};

type TraceListFilters = {
  page: number;
  status?: TraceStatus;
  userId?: number;
  correlationId?: string;
};

const EMPTY_TRACE_PAGE: TraceListResponse = {
  items: [],
  page: 0,
  size: 10,
  total: 0,
  hasMore: false,
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

const TRACE_RANGE_EVENT = "chtholly-trace-range-change";

function parseRangeSearch(search: string): DateRange | null {
  if (!search) return null;
  if (search === "__default__") return quickRange(7);
  const params = new URLSearchParams(search);
  const from = params.get("from");
  const to = params.get("to");
  if (from && to) {
    const preset = params.get("preset");
    return {
      from,
      to,
      preset: preset === "7d" || preset === "30d" ? preset : "custom",
    };
  }
  return quickRange(7);
}

function parseListFilters(search: string): TraceListFilters {
  if (!search || search === "__default__") return { page: 0 };
  const params = new URLSearchParams(search);
  const rawStatus = params.get("status");
  const status = ["SUCCESS", "FAILURE", "TIMEOUT", "ABORTED"].includes(rawStatus ?? "")
    ? rawStatus as TraceStatus
    : undefined;
  const rawUserId = params.get("userId");
  const parsedUserId = rawUserId ? Number(rawUserId) : undefined;
  const rawPage = Number(params.get("page") ?? 0);
  return {
    page: Number.isInteger(rawPage) && rawPage >= 0 ? rawPage : 0,
    status,
    userId: Number.isSafeInteger(parsedUserId) && (parsedUserId ?? 0) > 0 ? parsedUserId : undefined,
    correlationId: params.get("correlationId")?.trim() || undefined,
  };
}

function subscribeRangeSearch(onStoreChange: () => void) {
  window.addEventListener("popstate", onStoreChange);
  window.addEventListener(TRACE_RANGE_EVENT, onStoreChange);
  return () => {
    window.removeEventListener("popstate", onStoreChange);
    window.removeEventListener(TRACE_RANGE_EVENT, onStoreChange);
  };
}

function getRangeSearchSnapshot() {
  return window.location.search || "__default__";
}

function getServerRangeSearchSnapshot() {
  return "";
}

function updateRangeUrl(range: DateRange) {
  const url = new URL(window.location.href);
  url.searchParams.set("from", range.from);
  url.searchParams.set("to", range.to);
  url.searchParams.set("preset", range.preset);
  url.searchParams.delete("page");
  window.history.replaceState(null, "", url.toString());
  window.dispatchEvent(new Event(TRACE_RANGE_EVENT));
}

function updateListUrl(filters: Partial<TraceListFilters>) {
  const url = new URL(window.location.href);
  for (const [key, value] of Object.entries(filters)) {
    if (value == null || value === "" || (key === "page" && value === 0)) {
      url.searchParams.delete(key);
    } else {
      url.searchParams.set(key, String(value));
    }
  }
  window.history.replaceState(null, "", url.toString());
  window.dispatchEvent(new Event(TRACE_RANGE_EVENT));
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
  onSelect,
}: {
  label: string;
  value: string;
  tone?: "success" | "danger" | "warn";
  onSelect?: () => void;
}) {
  const content = (
    <>
      <div className="text-sm text-text-secondary">{label}</div>
      <div
        className={cn(
          "mt-2 text-2xl font-semibold",
          tone === "success" && "text-emerald-600",
          tone === "danger" && "text-rose-600",
          tone === "warn" && "text-amber-600",
        )}
      >
        {value}
      </div>
    </>
  );
  return onSelect ? (
    <button type="button" className="post-card p-5 text-left" onClick={onSelect}>
      {content}
    </button>
  ) : <div className="post-card p-5">{content}</div>;
}

export default function TraceDashboardPage() {
  const rangeSearch = useSyncExternalStore(
    subscribeRangeSearch,
    getRangeSearchSnapshot,
    getServerRangeSearchSnapshot,
  );
  const range = useMemo(() => parseRangeSearch(rangeSearch), [rangeSearch]);
  const listFilters = useMemo(() => parseListFilters(rangeSearch), [rangeSearch]);
  const [stats, setStats] = useState<TraceStats | null>(null);
  const [patterns, setPatterns] = useState<FailurePattern[]>([]);
  const [tokenTrends, setTokenTrends] = useState<TraceTokenTrendRow[]>([]);
  const [tracePage, setTracePage] = useState<TraceListResponse>(EMPTY_TRACE_PAGE);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (range) updateRangeUrl(range);
  }, [range]);

  const load = useCallback(async (
    nextRange: DateRange,
    nextFilters: TraceListFilters,
    isAlive: () => boolean,
  ) => {
    setLoading(true);
    setError(null);
    try {
      const [statsResp, patternsResp, tokenResp, listResp] = await Promise.all([
        traceService.getTraceStats(nextRange.from, nextRange.to),
        traceService.getTracePatterns(nextRange.from, nextRange.to),
        traceService.getTokenTrends(nextRange.from, nextRange.to),
        traceService.list({
          page: nextFilters.page,
          size: 10,
          status: nextFilters.status,
          userId: nextFilters.userId,
          correlationId: nextFilters.correlationId,
          from: nextRange.from,
          to: nextRange.to,
        }),
      ]);
      if (!isAlive()) return;
      setStats(statsResp);
      setPatterns(patternsResp);
      setTokenTrends(tokenResp);
      setTracePage(listResp);
    } catch (e) {
      if (!isAlive()) return;
      setError(e instanceof Error ? e.message : "加载 Trace 数据失败");
    } finally {
      if (isAlive()) setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!range) return;
    let alive = true;
    const timer = window.setTimeout(() => void load(range, listFilters, () => alive), 0);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [listFilters, load, range]);

  const executionTrend = useMemo(
    () => (range ? mergeExecutionTrend(range, stats?.executionTrend) : []),
    [range, stats?.executionTrend],
  );
  const tokenTrend = useMemo(
    () =>
      range ? mergeTokenTrend(range, tokenTrends.length ? tokenTrends : stats?.tokenTrend) : [],
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

        {range && (
          <form
            key={`${listFilters.correlationId ?? ""}:${listFilters.userId ?? ""}`}
            className="mt-5 grid gap-3 border-t border-border/60 pt-5 md:grid-cols-[160px_minmax(220px,1fr)_160px_auto]"
            onSubmit={(event) => {
              event.preventDefault();
              const data = new FormData(event.currentTarget);
              const correlationId = String(data.get("correlationId") ?? "").trim();
              const rawUserId = String(data.get("userId") ?? "").trim();
              const userId = rawUserId ? Number(rawUserId) : undefined;
              updateListUrl({
                page: 0,
                correlationId: correlationId || undefined,
                userId: Number.isSafeInteger(userId) && (userId ?? 0) > 0 ? userId : undefined,
              });
            }}
          >
            <select
              aria-label="Trace 状态"
              value={listFilters.status ?? ""}
              onChange={(event) => updateListUrl({
                page: 0,
                status: (event.target.value || undefined) as TraceStatus | undefined,
              })}
              className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-text"
            >
              <option value="">全部状态</option>
              <option value="SUCCESS">成功</option>
              <option value="FAILURE">失败</option>
              <option value="TIMEOUT">超时</option>
              <option value="ABORTED">中止</option>
            </select>
            <input
              name="correlationId"
              aria-label="Correlation ID"
              defaultValue={listFilters.correlationId ?? ""}
              placeholder="精确 Correlation ID"
              className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-text"
            />
            <input
              name="userId"
              aria-label="用户 ID"
              inputMode="numeric"
              defaultValue={listFilters.userId ?? ""}
              placeholder="用户 ID"
              className="rounded-md border border-border bg-surface px-3 py-2 text-sm text-text"
            />
            <div className="flex gap-2">
              <button type="submit" className="admin-btn">查询</button>
              <button
                type="button"
                className="admin-btn admin-btn--neutral"
                onClick={() => updateListUrl({ page: 0, status: undefined, userId: undefined, correlationId: undefined })}
              >
                清除
              </button>
            </div>
          </form>
        )}
      </div>

      {error && <div className="post-card p-4 mb-6 text-rose-600 text-sm">{error}</div>}

      {loading || !range ? (
        <div className="post-card p-8 text-center text-text-secondary">加载中...</div>
      ) : (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-6 mb-6">
            <StatCard label="总执行次数" value={formatNumber(stats?.totalExecutions)} />
            <StatCard
              label="成功执行"
              value={`${formatNumber(stats?.successCount)} · ${(stats?.successRate ?? 0).toFixed(1)}%`}
              tone="success"
              onSelect={() => updateListUrl({ page: 0, status: "SUCCESS" })}
            />
            <StatCard
              label="失败执行"
              value={formatNumber(stats?.failureCount)}
              tone="danger"
              onSelect={() => updateListUrl({ page: 0, status: "FAILURE" })}
            />
            <StatCard
              label="超时执行"
              value={formatNumber(stats?.timeoutCount)}
              tone="warn"
              onSelect={() => updateListUrl({ page: 0, status: "TIMEOUT" })}
            />
            <StatCard
              label="中止执行"
              value={formatNumber(stats?.abortedCount)}
              onSelect={() => updateListUrl({ page: 0, status: "ABORTED" })}
            />
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

          <section className="post-card p-5 mb-6">
            <div className="flex items-start justify-between gap-4 mb-4">
              <div>
                <h2 className="text-lg font-semibold">失败定位</h2>
                <p className="mt-1 text-sm text-text-secondary">从聚合模式直接进入可复现的样本 Trace。</p>
              </div>
              <span className="text-sm text-text-secondary">{patterns.length} 个模式</span>
            </div>
            {patterns.length === 0 ? (
              <p className="py-5 text-sm text-text-secondary">当前范围没有失败模式。</p>
            ) : (
              <div className="grid gap-3 lg:grid-cols-2">
                {patterns.slice(0, 6).map((pattern) => (
                  <article key={pattern.patternKey} className="rounded-lg border border-border/70 bg-surface/60 p-4">
                    <div className="flex items-start justify-between gap-3">
                      <strong className="font-mono text-sm">{pattern.patternKey}</strong>
                      <span className="text-sm text-rose-600">{pattern.occurrenceCount} 次</span>
                    </div>
                    {pattern.resolutionHint && (
                      <p className="mt-2 text-sm leading-6 text-text-secondary">{pattern.resolutionHint}</p>
                    )}
                    <div className="mt-3 flex flex-wrap gap-2">
                      {pattern.sampleTraceIds.map((correlationId) => (
                        <Link
                          key={correlationId}
                          href={`/admin/traces/${encodeURIComponent(correlationId)}`}
                          aria-label={`查看样本 ${correlationId}`}
                          className="rounded-md bg-cloud px-2 py-1 font-mono text-xs text-sky hover:text-sky-deep"
                        >
                          {correlationId.slice(0, 12)}
                        </Link>
                      ))}
                    </div>
                  </article>
                ))}
              </div>
            )}
          </section>

          <section className="post-card p-5 overflow-x-auto">
            <div className="flex items-center justify-between gap-4 mb-4">
              <div>
                <h2 className="text-lg font-semibold">执行记录</h2>
                <p className="mt-1 text-sm text-text-secondary">共 {formatNumber(tracePage.total)} 条，点击 ID 查看执行层级。</p>
              </div>
            </div>
            <table className="w-full text-sm border-collapse min-w-[640px]">
              <thead>
                <tr className="border-b border-border text-left text-text-secondary">
                  <th className="py-2 pr-3">corrId</th>
                  <th className="py-2 pr-3">user</th>
                  <th className="py-2 pr-3">status</th>
                  <th className="py-2 pr-3">steps</th>
                  <th className="py-2 pr-3">dur</th>
                  <th className="py-2">time</th>
                </tr>
              </thead>
              <tbody>
                {tracePage.items.length === 0 ? (
                  <tr>
                    <td colSpan={6} className="py-6 text-center text-text-secondary">
                      暂无 trace 记录
                    </td>
                  </tr>
                ) : (
                  tracePage.items.map((trace) => (
                    <tr key={trace.correlationId} className="border-b border-border/60">
                      <td className="py-2 pr-3 font-mono text-xs">
                        <Link
                          href={`/admin/traces/${encodeURIComponent(trace.correlationId)}`}
                          className="text-sky hover:text-sky-deep"
                        >
                          {trace.correlationId.slice(0, 12)}{trace.correlationId.length > 12 ? "…" : ""}
                        </Link>
                      </td>
                      <td className="py-2 pr-3">{trace.userId ?? "-"}</td>
                      <td className={cn("py-2 pr-3 font-medium", statusClass(trace.status))}>
                        {trace.status}
                      </td>
                      <td className="py-2 pr-3">{trace.stepsCount ?? 0}</td>
                      <td className="py-2 pr-3">{formatMs(trace.durationMs)}</td>
                      <td className="py-2 text-text-secondary">
                        {trace.startedAt ? formatDate(trace.startedAt) : "-"}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
            <div className="mt-4 flex items-center justify-between border-t border-border/60 pt-4 text-sm text-text-secondary">
              <span>第 {tracePage.page + 1} 页</span>
              <div className="flex gap-2">
                <button
                  type="button"
                  className="admin-btn admin-btn--neutral"
                  disabled={listFilters.page <= 0}
                  onClick={() => updateListUrl({ page: Math.max(0, listFilters.page - 1) })}
                  aria-label="上一页"
                >
                  上一页
                </button>
                <button
                  type="button"
                  className="admin-btn"
                  disabled={!tracePage.hasMore}
                  onClick={() => updateListUrl({ page: listFilters.page + 1 })}
                  aria-label="下一页"
                >
                  下一页
                </button>
              </div>
            </div>
          </section>
        </>
      )}
    </div>
  );
}
