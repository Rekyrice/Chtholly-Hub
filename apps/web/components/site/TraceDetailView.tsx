"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { traceService } from "@/lib/services/traceService";
import type { TraceDetail, TraceEvent, TraceStep } from "@/lib/types/trace";

const COMPONENT_FIELDS = [
  ["prompt", "Prompt"],
  ["skillSelector", "Skill Selector"],
  ["model", "模型"],
  ["retrieval", "检索"],
  ["tools", "工具协议"],
  ["traceSchema", "Trace Schema"],
] as const;

const RETRIEVAL_ROUTES = ["semantic", "keyword", "entity"] as const;

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

  const metadata = projectTracePayload(trace.tracePayload);

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
        <SummaryItem label="运行模式" value={metadata.runMode ?? "-"} />
        <SummaryItem label="失败类型" value={metadata.failureType ?? "-"} mono />
        <SummaryItem label="LLM / Tool 调用" value={formatCallCount(metadata)} />
      </dl>

      {trace.errorMessage && <div className="admin-alert">{trace.errorMessage}</div>}

      {metadata.hasStructuredMetadata && <TraceMetadataSection metadata={metadata} />}

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

function TraceMetadataSection({ metadata }: { metadata: TracePayloadMetadata }) {
  return (
    <section className="trace-unassigned" aria-labelledby="trace-metadata-title">
      <header>
        <h2 id="trace-metadata-title">Trace 运行元数据</h2>
        <p>仅展示版本、固定状态和 Evidence 标识；正文与原始输入不会投影到摘要区。</p>
      </header>

      <dl className="trace-detail-summary">
        {metadata.components.map((component) => (
          <SummaryItem
            key={component.key}
            label={component.label}
            value={component.value}
            mono
          />
        ))}
        {metadata.skill && (
          <>
            <SummaryItem label="Skill" value={formatSkill(metadata.skill)} mono />
            <SummaryItem
              label="选择 / 校验"
              value={`${metadata.skill.selectionStatus ?? "-"} / ${metadata.skill.validationStatus ?? "-"}`}
            />
          </>
        )}
        {metadata.retrieval && (
          <>
            <SummaryItem label="检索策略" value={metadata.retrieval.strategy ?? "-"} mono />
            <SummaryItem label="Evidence" value={metadata.retrieval.evidenceCount ?? "-"} />
            <SummaryItem
              label="Evidence 快照"
              value={metadata.retrieval.evidenceSnapshotHash ?? "-"}
              mono
            />
            <SummaryItem
              label="检索降级"
              value={metadata.retrieval.degraded == null
                ? "-"
                : metadata.retrieval.degraded ? "是" : "否"}
            />
            <SummaryItem
              label="引用校验"
              value={metadata.retrieval.citationValidationStatus ?? "-"}
            />
          </>
        )}
      </dl>

      {metadata.retrieval && metadata.retrieval.statuses.length > 0 && (
        <article className="trace-event trace-event--tool">
          <header><strong>检索路由状态</strong></header>
          <dl className="trace-event__tokens">
            {metadata.retrieval.statuses.map((route) => (
              <div key={route.route}>
                <dt>{route.route}</dt>
                <dd>{`${route.route}: ${route.status}`}</dd>
              </div>
            ))}
          </dl>
        </article>
      )}

      {metadata.retrieval && metadata.retrieval.evidence.length > 0 && (
        <div className="trace-event-list" aria-label="Evidence 元数据">
          {metadata.retrieval.evidence.map((evidence, index) => (
            <article className="trace-event trace-event--tool" key={`${evidence.citationId}-${index}`}>
              <header>
                <strong>{`${evidence.citationId ?? "-"} · ${evidence.documentId ?? "-"}`}</strong>
              </header>
              <dl className="trace-event__tokens">
                <div><dt>来源</dt><dd>{evidence.source ?? "-"}</dd></div>
                <div><dt>来源版本</dt><dd>{evidence.sourceVersion ?? "-"}</dd></div>
                <div><dt>来源摘要</dt><dd className="trace-mono">{evidence.sourceHash ?? "-"}</dd></div>
              </dl>
            </article>
          ))}
        </div>
      )}
    </section>
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

type SafeRecord = Record<string, unknown>;

type TracePayloadMetadata = {
  runMode: string | null;
  failureType: string | null;
  llmCallCount: number | null;
  toolCallCount: number | null;
  components: Array<{ key: string; label: string; value: string }>;
  skill: {
    id: string | null;
    version: string | null;
    selectionStatus: string | null;
    validationStatus: string | null;
  } | null;
  retrieval: {
    strategy: string | null;
    evidenceCount: number | null;
    evidenceSnapshotHash: string | null;
    degraded: boolean | null;
    citationValidationStatus: string | null;
    statuses: Array<{ route: string; status: string }>;
    evidence: Array<{
      citationId: string | null;
      documentId: string | null;
      source: string | null;
      sourceVersion: string | null;
      sourceHash: string | null;
    }>;
  } | null;
  hasStructuredMetadata: boolean;
};

function projectTracePayload(payload: unknown): TracePayloadMetadata {
  const root = asRecord(payload);
  const componentRecord = asRecord(root?.components);
  const components = componentRecord
    ? COMPONENT_FIELDS.flatMap(([key, label]) => {
        const value = boundedString(componentRecord[key]);
        return value ? [{ key, label, value }] : [];
      })
    : [];

  const skillRecord = asRecord(root?.skill);
  const skill = skillRecord ? {
    id: boundedString(skillRecord.id),
    version: boundedString(skillRecord.version),
    selectionStatus: boundedString(skillRecord.selectionStatus),
    validationStatus: boundedString(skillRecord.validationStatus),
  } : null;
  const projectedSkill = skill && Object.values(skill).some((value) => value != null) ? skill : null;

  const retrievalRecord = asRecord(root?.retrieval);
  const statusesRecord = asRecord(retrievalRecord?.statuses);
  const statuses = statusesRecord
    ? RETRIEVAL_ROUTES.flatMap((route) => {
        const status = boundedString(statusesRecord[route]);
        return status ? [{ route, status }] : [];
      })
    : [];
  const evidence = Array.isArray(retrievalRecord?.evidence)
    ? retrievalRecord.evidence.slice(0, 20).flatMap((value) => {
        const record = asRecord(value);
        if (!record) return [];
        const item = {
          citationId: boundedString(record.citationId),
          documentId: boundedString(record.documentId),
          source: boundedString(record.source),
          sourceVersion: boundedString(record.sourceVersion),
          sourceHash: boundedString(record.sourceHash),
        };
        return Object.values(item).some((field) => field != null) ? [item] : [];
      })
    : [];
  const retrieval = retrievalRecord ? {
    strategy: boundedString(retrievalRecord.strategy),
    evidenceCount: nonNegativeInteger(retrievalRecord.evidenceCount),
    evidenceSnapshotHash: boundedString(retrievalRecord.evidenceSnapshotHash),
    degraded: typeof retrievalRecord.degraded === "boolean" ? retrievalRecord.degraded : null,
    citationValidationStatus: boundedString(retrievalRecord.citationValidationStatus),
    statuses,
    evidence,
  } : null;
  const projectedRetrieval = retrieval && (
    retrieval.strategy != null
    || retrieval.evidenceCount != null
    || retrieval.evidenceSnapshotHash != null
    || retrieval.degraded != null
    || retrieval.citationValidationStatus != null
    || retrieval.statuses.length > 0
    || retrieval.evidence.length > 0
  ) ? retrieval : null;

  return {
    runMode: boundedString(root?.runMode),
    failureType: boundedString(root?.failureType),
    llmCallCount: nonNegativeInteger(root?.llmCallCount),
    toolCallCount: Array.isArray(root?.toolCalls) ? root.toolCalls.length : null,
    components,
    skill: projectedSkill,
    retrieval: projectedRetrieval,
    hasStructuredMetadata: components.length > 0 || projectedSkill != null || projectedRetrieval != null,
  };
}

function asRecord(value: unknown): SafeRecord | null {
  return value != null && typeof value === "object" && !Array.isArray(value)
    ? value as SafeRecord
    : null;
}

function boundedString(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const normalized = value.trim();
  return normalized ? normalized.slice(0, 256) : null;
}

function nonNegativeInteger(value: unknown): number | null {
  return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

function formatCallCount(metadata: TracePayloadMetadata) {
  if (metadata.llmCallCount == null && metadata.toolCallCount == null) return "-";
  return `${metadata.llmCallCount ?? "-"} / ${metadata.toolCallCount ?? "-"}`;
}

function formatSkill(skill: NonNullable<TracePayloadMetadata["skill"]>) {
  if (skill.id && skill.version) return `${skill.id} · ${skill.version}`;
  return skill.id ?? skill.version ?? "-";
}
