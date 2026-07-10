"use client";

import { RotateCcw } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useAsyncAction } from "@/lib/hooks/useAsyncAction";
import { extractErrorMessage } from "@/lib/hooks/useErrorMessage";
import { deadLetterService } from "@/lib/services/deadLetterService";
import type { DeadLetterMessage, DeadLetterPageResponse } from "@/lib/types/deadLetter";
import { cn, formatDate } from "@/lib/utils";

const PAGE_SIZE = 20;

function emptyPage(): DeadLetterPageResponse {
  return {
    items: [],
    total: 0,
    page: 1,
    size: PAGE_SIZE,
    hasMore: false,
  };
}

function shortText(value: string | undefined, max = 110) {
  if (!value) return "-";
  const compact = value.replace(/\s+/g, " ").trim();
  return compact.length > max ? `${compact.slice(0, max)}...` : compact;
}

function statusTone(status?: string) {
  const normalized = status?.toUpperCase();
  if (normalized === "PENDING" || normalized === "RETRYING") return "admin-badge--warn";
  if (normalized === "DEAD" || normalized === "FAILED") return "admin-badge--danger";
  return "admin-badge--ok";
}

export default function DeadLetterTable() {
  const [page, setPage] = useState(1);
  const [data, setData] = useState<DeadLetterPageResponse>(emptyPage);
  const [busyId, setBusyId] = useState<string | null>(null);

  const { execute: load, loading, error, setError } = useAsyncAction(
    (nextPage: number) => deadLetterService.list(nextPage, PAGE_SIZE),
    {
      fallbackError: "死信列表加载失败",
      onSuccess: setData,
    },
  );

  useEffect(() => {
    void load(page);
  }, [load, page]);

  const maxPage = useMemo(
    () => Math.max(1, Math.ceil((data.total || data.items.length) / PAGE_SIZE)),
    [data.items.length, data.total],
  );

  const updateMessage = (id: string, patch: Partial<DeadLetterMessage>) => {
    setData((current) => ({
      ...current,
      items: current.items.map((item) => (item.id === id ? { ...item, ...patch } : item)),
    }));
  };

  const handleReplay = async (message: DeadLetterMessage) => {
    setBusyId(message.id);
    setError(null);
    try {
      await deadLetterService.replay(message.id);
      updateMessage(message.id, { status: "PENDING" });
    } catch (err) {
      setError(extractErrorMessage(err, "死信重放失败"));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="admin-page">
      <header className="admin-page__header">
        <div>
          <p className="admin-page__eyebrow">Dead Letter Queue</p>
          <h1>死信队列</h1>
          <p>查看消费失败的消息，必要时将单条消息重新投递回原 topic。</p>
        </div>
      </header>

      {error && <div className="admin-alert">{error}</div>}

      <section className="admin-panel">
        <div className="admin-toolbar">
          <span className="admin-toolbar__meta">共 {data.total || data.items.length} 条死信消息</span>
        </div>

        <div className="admin-table-wrap" aria-busy={loading}>
          <table className="admin-table dead-letter-table">
            <thead>
              <tr>
                <th>Topic</th>
                <th>Payload</th>
                <th>失败原因</th>
                <th>重试</th>
                <th>状态</th>
                <th>创建时间</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={7} className="admin-table__empty">
                    加载中...
                  </td>
                </tr>
              ) : data.items.length === 0 ? (
                <tr>
                  <td colSpan={7} className="admin-table__empty">
                    暂无死信消息
                  </td>
                </tr>
              ) : (
                data.items.map((message) => (
                  <tr key={message.id}>
                    <td>
                      <div className="dead-letter-topic">
                        <strong>{message.topic}</strong>
                        {message.messageKey && <small>{message.messageKey}</small>}
                      </div>
                    </td>
                    <td>
                      <code className="dead-letter-payload">{shortText(message.payload)}</code>
                    </td>
                    <td>
                      <span className="dead-letter-reason">{shortText(message.failureReason, 150)}</span>
                    </td>
                    <td>{message.retryCount}</td>
                    <td>
                      <span className={cn("admin-badge", statusTone(message.status))}>
                        {message.status || "UNKNOWN"}
                      </span>
                    </td>
                    <td>{message.createdAt ? formatDate(message.createdAt) : "-"}</td>
                    <td>
                      <button
                        type="button"
                        className="admin-btn"
                        disabled={busyId === message.id}
                        onClick={() => void handleReplay(message)}
                      >
                        <RotateCcw size={14} />
                        <span>{busyId === message.id ? "重放中" : "重放"}</span>
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        <div className="admin-pagination">
          <button type="button" disabled={page <= 1 || loading} onClick={() => setPage((p) => p - 1)}>
            上一页
          </button>
          <span>
            第 {page} / {maxPage} 页
          </span>
          <button
            type="button"
            disabled={(!data.hasMore && page >= maxPage) || loading}
            onClick={() => setPage((p) => p + 1)}
          >
            下一页
          </button>
        </div>
      </section>
    </div>
  );
}
