"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { traceService } from "@/lib/services/traceService";
import type { TraceDetail, TraceEvent, TraceStep } from "@/lib/types/trace";

export default function TraceDetailView({ correlationId }: { correlationId: string }) {
  const [trace, setTrace] = useState<TraceDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void traceService.detail(correlationId)
      .then((detail) => {
        if (alive) setTrace(detail);
      })
      .catch((reason) => {
        if (alive) setError(reason instanceof Error ? reason.message : "加载 Trace 详情失败");
      });
    return () => {
      alive = false;
    };
  }, [correlationId]);

  if (error) {
    return (
      <div className="trace-detail-page">
        <Link href="/admin/traces" className="trace-detail-back">← 返回 Trace 总览</Link>
        <div className="admin-alert">{error}</div>
      </div>
    );
  }

  if (!trace) {
    return <div className="admin-loading-card">正在加载 Trace 执行层级……</div>;
  }

  return (
    <div className="trace-detail-page">
      <Link href="/admin/traces" className="trace-detail-back">← 返回 Trace 总览</Link>

      <header className="trace-detail-header">
        <div>
          <p className="admin-page__eyebrow">Execution trace</p>
          <h1>{trace.correlationId}</h1>
          <p>一次 Agent 执行内的 Step、模型调用、工具与观察结果。</p>
        </div>
        <span className={`trace-status trace-status--${trace.status.toLowerCase()}`}>
          {trace.status}
        </span>
      </header>

      <dl className="trace-detail-summary">
        <SummaryItem label="用户" value={trace.userId ?? "-"} />
        <SummaryItem label="会话" value={trace.sessionId ?? "-"} mono />
        <SummaryItem label="步骤" value={trace.stepsCount ?? trace.steps.length} />
        <SummaryItem label="总耗时" value={formatMs(trace.durationMs)} />
      </dl>

      {trace.errorMessage && <div className="admin-alert">{trace.errorMessage}</div>}

      <section className="trace-timeline" aria-label="Trace 执行时间线">
        {trace.steps.length === 0 ? (
          <div className="trace-empty">这条 Trace 没有可解析的 Step 层级。</div>
        ) : (
          trace.steps.map((step) => <TraceStepCard key={step.stepIndex} step={step} />)
        )}
      </section>

      {trace.unassignedEvents.length > 0 && (
        <section className="trace-unassigned">
          <header>
            <h2>未分配事件（旧数据）</h2>
            <p>这些事件没有明确 stepIndex，因此不推测归属。</p>
          </header>
          <div className="trace-event-list">
            {trace.unassignedEvents.map((event, index) => (
              <TraceEventCard key={`${event.type}-${event.sequence ?? index}`} event={event} />
            ))}
          </div>
        </section>
      )}

      <details className="trace-raw">
        <summary>查看原始 Trace JSON</summary>
        <pre>{JSON.stringify(trace.tracePayload, null, 2)}</pre>
      </details>
    </div>
  );
}

function SummaryItem({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string | number;
  mono?: boolean;
}) {
  return (
    <div>
      <dt>{label}</dt>
      <dd className={mono ? "trace-mono" : undefined}>{value}</dd>
    </div>
  );
}

function TraceStepCard({ step }: { step: TraceStep }) {
  const finalStep = step.action === "final_answer";
  return (
    <article className={`trace-step${finalStep ? " trace-step--final" : ""}`}>
      <header className="trace-step__header">
        <div>
          <span>Step {step.stepIndex + 1}</span>
          <h2>{finalStep ? "最终回答" : step.action || "未命名动作"}</h2>
        </div>
        <div className="trace-step__metrics">
          <span>LLM {formatMs(step.llmDurationMs)}</span>
          {(step.toolDurationMs ?? 0) > 0 && <span>Tool {formatMs(step.toolDurationMs)}</span>}
        </div>
      </header>
      {step.events.length > 0 ? (
        <div className="trace-event-list">
          {step.events.map((event, index) => (
            <TraceEventCard key={`${event.type}-${event.sequence ?? index}`} event={event} />
          ))}
        </div>
      ) : (
        <p className="trace-step__empty">这个 Step 没有独立事件明细。</p>
      )}
    </article>
  );
}

function TraceEventCard({ event }: { event: TraceEvent }) {
  const isTool = event.type === "tool";
  return (
    <article className={`trace-event trace-event--${event.type}`}>
      <header>
        <div>
          <span className="trace-event__sequence">#{event.sequence ?? "-"}</span>
          <strong>{isTool ? `工具 · ${event.name || "unknown"}` : "LLM 决策"}</strong>
        </div>
        <div className="trace-event__meta">
          {isTool && event.success != null && (
            <span className={event.success ? "trace-event__ok" : "trace-event__fail"}>
              {event.success ? "成功" : "失败"}
            </span>
          )}
          <span>{formatMs(event.durationMs)}</span>
        </div>
      </header>

      {!isTool && (
        <dl className="trace-event__tokens">
          <div><dt>Input chars</dt><dd>{event.inputChars ?? "-"}</dd></div>
          <div><dt>Output chars</dt><dd>{event.outputChars ?? "-"}</dd></div>
          <div><dt>TTFT</dt><dd>{formatMs(event.firstTokenMs)}</dd></div>
        </dl>
      )}

      {event.inputSummary && (
        <div className="trace-event__block">
          <span>输入摘要</span>
          <pre>{event.inputSummary}</pre>
        </div>
      )}
      {event.observationSummary && (
        <div className="trace-event__block trace-event__observation">
          <span>Observation</span>
          <p>{event.observationSummary}</p>
        </div>
      )}
    </article>
  );
}

function formatMs(value: number | null | undefined) {
  if (value == null) return "-";
  return value < 1000 ? `${value}ms` : `${(value / 1000).toFixed(1)}s`;
}
