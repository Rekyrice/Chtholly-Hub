"use client";

import { useEffect, useMemo, useState } from "react";
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { adminService } from "@/lib/services/adminService";
import type { AdminStats } from "@/lib/types/admin";

type TrendRow = {
  day: string;
  users: number;
  posts: number;
};

const chartColors = {
  users: "#4ab0d9",
  posts: "#e87461",
  grid: "rgba(148, 163, 184, 0.24)",
};

function formatNumber(value: number | undefined) {
  return new Intl.NumberFormat("zh-CN").format(value ?? 0);
}

function makeTrend(stats: AdminStats | null): TrendRow[] {
  const totalUsers = stats?.totalUsers ?? 0;
  const totalPosts = stats?.totalPosts ?? 0;
  const activeUsers7d = stats?.activeUsers7d ?? 0;
  const newPosts7d = stats?.newPosts7d ?? 0;
  const today = new Date();

  return Array.from({ length: 7 }, (_, index) => {
    const date = new Date(today);
    date.setDate(today.getDate() - (6 - index));
    const progress = (index + 1) / 7;
    const userBase = Math.max(0, totalUsers - activeUsers7d);
    const postBase = Math.max(0, totalPosts - newPosts7d);
    return {
      day: `${date.getMonth() + 1}/${date.getDate()}`,
      users: Math.round(userBase + activeUsers7d * progress),
      posts: Math.round(postBase + newPosts7d * progress),
    };
  });
}

function AdminStatCard({
  label,
  value,
  note,
}: {
  label: string;
  value: string;
  note?: string;
}) {
  return (
    <article className="admin-stat-card">
      <span>{label}</span>
      <strong>{value}</strong>
      {note && <small>{note}</small>}
    </article>
  );
}

export default function AdminOverview() {
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    void adminService
      .getStats()
      .then((data) => {
        if (!alive) return;
        setStats(data);
        setError(null);
      })
      .catch((err) => {
        if (!alive) return;
        setError(err instanceof Error ? err.message : "统计数据加载失败");
      })
      .finally(() => {
        if (alive) setLoading(false);
      });

    return () => {
      alive = false;
    };
  }, []);

  const trend = useMemo(() => makeTrend(stats), [stats]);

  return (
    <div className="admin-page">
      <header className="admin-page__header">
        <div>
          <p className="admin-page__eyebrow">Admin Console</p>
          <h1>统计概览</h1>
          <p>快速查看社区规模、内容增长和最近活跃情况。</p>
        </div>
      </header>

      {error && <div className="admin-alert">{error}</div>}

      <section className="admin-stats-grid" aria-busy={loading}>
        <AdminStatCard label="总用户数" value={loading ? "-" : formatNumber(stats?.totalUsers)} />
        <AdminStatCard label="总文章数" value={loading ? "-" : formatNumber(stats?.totalPosts)} />
        <AdminStatCard label="总评论数" value={loading ? "-" : formatNumber(stats?.totalComments)} />
        <AdminStatCard
          label="近 7 天活跃"
          value={loading ? "-" : formatNumber(stats?.activeUsers7d)}
          note="若后端未提供 7 天口径，则使用今日新增代理"
        />
        <AdminStatCard
          label="近 7 天新文章"
          value={loading ? "-" : formatNumber(stats?.newPosts7d)}
          note={stats?.commentsToday != null ? `今日评论 ${formatNumber(stats.commentsToday)}` : undefined}
        />
      </section>

      <section className="admin-panel">
        <div className="admin-panel__header">
          <div>
            <h2>增长趋势</h2>
            <p>当前后端只返回汇总数据，图表按近 7 天增量做轻量展示。</p>
          </div>
        </div>
        <div className="admin-chart">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={trend} margin={{ top: 16, right: 20, bottom: 4, left: 0 }}>
              <CartesianGrid stroke={chartColors.grid} vertical={false} />
              <XAxis dataKey="day" stroke="var(--color-text-secondary)" tickLine={false} />
              <YAxis stroke="var(--color-text-secondary)" tickLine={false} allowDecimals={false} />
              <Tooltip
                contentStyle={{
                  background: "var(--color-surface)",
                  border: "1px solid var(--color-border)",
                  borderRadius: 8,
                  color: "var(--color-text)",
                }}
              />
              <Line
                type="monotone"
                dataKey="users"
                name="用户数"
                stroke={chartColors.users}
                strokeWidth={2}
                dot={false}
              />
              <Line
                type="monotone"
                dataKey="posts"
                name="文章数"
                stroke={chartColors.posts}
                strokeWidth={2}
                dot={false}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      </section>
    </div>
  );
}
