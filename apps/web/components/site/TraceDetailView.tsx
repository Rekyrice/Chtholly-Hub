"use client";

import Link from "next/link";
import { useEffect, useState, type CSSProperties } from "react";
import { traceService } from "@/lib/services/traceService";
import type {
  TraceDetail,
  TraceContent,
  TraceEvent,
  TraceLifecycleDetails,
  TraceLlmDetails,
  TraceMetadata,
  TracePhase,
  TraceToolDetails,
} from "@/lib/types/trace";

const PHASE_LABELS: Record<string, string> = {
  accepted: "接收请求",
  skill: "选择能力",
  plan: "规划工具",
  retrieval: "检索证据",
  llm: "模型调用",
  tool: "工具执行",
  validation: "校验答案",
  memory: "写入记忆",
  delivery: "交付结果",
};

const COMPONENT_FIELDS = [
  ["prompt", "Prompt"],
  ["skillSelector", "Skill Selector"],
  ["model", "模型"],
  ["retrieval", "检索"],
  ["citationValidator", "引用校验"],
  ["tools", "工具协议"],
  ["traceSchema", "Trace Schema"],
] as const;

const LIFECYCLE_DETAIL_LABELS: Record<string, string> = {
  model: "模型",
  runMode: "运行模式",
  skillId: "Skill",
  skillVersion: "Skill 版本",
  sourceCount: "检索源",
  evidenceCount: "Evidence",
  reason: "原因",
  toolCount: "工具数",
  budgetMs: "单轮预算",
  stage: "阶段",
  terminalType: "终态类型",
  deliveryCode: "交付代码",
  modelFirstTokenMs: "模型首字",
  safeAnswerReadyMs: "安全答案就绪",
  firstClientDeltaMs: "客户端首字",
  answerChars: "回答字符",
};

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

  const eventCount = trace.phases.reduce((total, phase) => total + phase.events.length, 0);
  const unreadable = trace.compatibility === "MALFORMED" || trace.compatibility === "UNSUPPORTED";

  return (
    <div className="trace-detail-page">
      <Link href="/admin/traces" className="trace-detail-back">← 返回 Trace 总览</Link>

      <header className="trace-detail-header">
        <div>
          <p className="admin-page__eyebrow">Execution trace</p>
          <h1>{trace.correlationId}</h1>
          <p>从请求接收、模型决策到工具观察与最终交付，按一次真实执行展开。</p>
        </div>
        <div className="trace-detail-header__badges">
          <span className={`trace-compat trace-compat--${trace.compatibility.toLowerCase()}`}>
            {formatCompatibility(trace)}
          </span>
          <span className={`trace-status trace-status--${(trace.status || "unknown").toLowerCase()}`}>
            {trace.status || "UNKNOWN"}
          </span>
        </div>
      </header>

      <dl className="trace-detail-summary">
        <SummaryItem label="用户" value={trace.userId ?? "-"} />
        <SummaryItem label="会话" value={trace.sessionId ?? "-"} mono />
        <SummaryItem label="步骤" value={trace.stepsCount} />
        <SummaryItem label="事件" value={eventCount} />
        <SummaryItem label="总耗时" value={formatMs(trace.durationMs)} />
        <SummaryItem label="计时可信度" value={formatTimingAccuracy(trace.timingAccuracy)} />
        <SummaryItem label="运行模式" value={trace.metadata?.runMode ?? "-"} />
        <SummaryItem label="失败类型" value={trace.metadata?.failureType ?? "-"} mono />
      </dl>

      {trace.errorMessage && <div className="admin-alert">{trace.errorMessage}</div>}

      {trace.compatibility === "LEGACY_V3" && (
        <aside className="trace-compat-note trace-compat-note--legacy">
          <strong>旧数据没有可靠的开始时间，因此不会推测事件先后间隔。</strong>
          <span>页面只展示每次模型或工具调用自身的耗时与经过校验的摘要。</span>
        </aside>
      )}

      {unreadable ? (
        <TraceUnavailable compatibility={trace.compatibility} />
      ) : (
        <TraceWaterfall trace={trace} />
      )}

      {trace.metadata && <TraceMetadataSection metadata={trace.metadata} />}
    </div>
  );
}

function TraceWaterfall({ trace }: { trace: TraceDetail }) {
  const exact = trace.timingAccuracy === "EXACT";
  const totalMs = resolveTimelineDuration(trace);

  return (
    <section className="trace-waterfall" aria-labelledby="trace-waterfall-title">
      <header className="trace-section-heading">
        <div>
          <p className="admin-page__eyebrow">Execution waterfall</p>
          <h2 id="trace-waterfall-title">Trace 时间瀑布</h2>
        </div>
        <p>{exact ? `0 — ${formatMs(totalMs)}` : "按记录顺序 · 仅显示调用耗时"}</p>
      </header>

      {exact && (
        <div className="trace-waterfall__axis" aria-hidden="true">
          <span>0</span><span>25%</span><span>50%</span><span>75%</span><span>{formatMs(totalMs)}</span>
        </div>
      )}

      <div className="trace-waterfall__lanes">
        {trace.phases.length === 0 ? (
          <div className="trace-empty">这条 Trace 没有可展示的安全事件。</div>
        ) : (
          trace.phases.map((phase, index) => (
            <TracePhaseLane
              key={`${phase.phase}-${index}`}
              phase={phase}
              phaseIndex={index}
              exact={exact}
              totalMs={totalMs}
            />
          ))
        )}
      </div>
    </section>
  );
}

function TracePhaseLane({
  phase,
  phaseIndex,
  exact,
  totalMs,
}: {
  phase: TracePhase;
  phaseIndex: number;
  exact: boolean;
  totalMs: number;
}) {
  return (
    <section className="trace-waterfall__lane" aria-label={`${phaseLabel(phase.phase)}阶段`}>
      <header className="trace-waterfall__lane-label">
        <span>{String(phaseIndex + 1).padStart(2, "0")}</span>
        <div>
          <h3>{phaseLabel(phase.phase)}</h3>
          <p>{phase.events.length} 个事件</p>
        </div>
      </header>

      <div className="trace-waterfall__lane-body">
        <div className={`trace-waterfall__track${exact ? "" : " trace-waterfall__track--duration"}`}>
          {phase.events.map((event) => (
            <div
              className={`trace-waterfall__bar trace-waterfall__bar--${event.type}`}
              key={`bar-${event.id}`}
              style={eventBarStyle(event, exact, totalMs)}
              title={`${event.name} · ${formatMs(event.durationMs)}`}
            >
              <span>{event.name}</span>
            </div>
          ))}
        </div>

        <div className="trace-event-list">
          {phase.events.map((event) => (
            <TraceEventCard key={event.id} event={event} exact={exact} />
          ))}
        </div>
      </div>
    </section>
  );
}

function TraceEventCard({ event, exact }: { event: TraceEvent; exact: boolean }) {
  const label = event.type === "tool"
    ? `工具 ${event.name}`
    : `${eventTypeLabel(event.type)} ${event.name}`;

  return (
    <article className={`trace-event trace-event--${event.type}`} aria-label={label}>
      <header>
        <div>
          <span className="trace-event__sequence">#{event.sequence ?? "-"}</span>
          <div>
            <strong>{eventDisplayName(event)}</strong>
            <small>{event.name}</small>
          </div>
        </div>
        <div className="trace-event__meta">
          <span className={`trace-event__status trace-event__status--${statusTone(event.status)}`}>
            {event.status || "UNKNOWN"}
          </span>
          {exact && event.startedOffsetMs != null && <span>+{formatMs(event.startedOffsetMs)}</span>}
          <span>{formatMs(event.durationMs)}</span>
        </div>
      </header>

      <div className="trace-event__contract">
        {event.stepIndex != null && <span>Step {event.stepIndex + 1}</span>}
        {event.attempt != null && <span>Attempt {event.attempt}</span>}
        {event.budgetBeforeMs != null && (
          <span>预算 {formatMs(event.budgetBeforeMs)} → {formatMs(event.budgetAfterMs)}</span>
        )}
        {event.errorCode && <span className="trace-event__error-code">{event.errorCode}</span>}
      </div>

      {event.details && (
        <details className="trace-event__details">
          <summary>{event.type === "tool" ? "查看诊断" : event.type === "llm" ? "查看调用指标" : "查看阶段信息"}</summary>
          <TraceEventDetails event={event} />
        </details>
      )}
    </article>
  );
}

function TraceEventDetails({ event }: { event: TraceEvent }) {
  if (event.type === "llm") {
    const details = event.details as TraceLlmDetails;
    return (
      <div className="trace-llm-details">
        <dl className="trace-event__tokens trace-event__tokens--five">
          <Metric label="Purpose" value={details.purpose ?? "-"} />
          <Metric label="Model" value={details.model ?? "-"} mono />
          <Metric label="Input chars" value={details.inputChars ?? "-"} />
          <Metric label="Output chars" value={details.outputChars ?? "-"} />
          <Metric label="TTFT" value={formatMs(details.firstTokenMs)} />
        </dl>
        {details.failureClass && (
          <div className="trace-content-failure">异常类型 · {details.failureClass}</div>
        )}
        <TraceContentBlock label="System Prompt" content={details.systemPrompt} />
        <TraceContentBlock label="User Prompt" content={details.userPrompt} />
        <TraceContentBlock label="模型原始输出" content={details.rawOutput} />
        <TraceContentBlock label="异常消息" content={details.failureMessage} />
      </div>
    );
  }

  if (event.type === "tool") {
    return <TraceToolDiagnostics details={event.details as TraceToolDetails} />;
  }

  const details = event.details as TraceLifecycleDetails;
  const scalarEntries = Object.entries(details).filter(([key]) => key !== "finalAnswer");
  return (
    <div className="trace-lifecycle-details">
      <dl className="trace-event__tokens">
        {scalarEntries.map(([key, value]) => (
          <Metric
            key={key}
            label={LIFECYCLE_DETAIL_LABELS[key] ?? key}
            value={key.endsWith("Ms") && typeof value === "number" ? formatMs(value) : String(value)}
            mono={typeof value === "string"}
          />
        ))}
      </dl>
      <TraceContentBlock label="最终交付内容" content={details.finalAnswer} />
    </div>
  );
}

function TraceToolDiagnostics({ details }: { details: TraceToolDetails }) {
  const sanitizedEntries = details.sanitizedInput ? Object.entries(details.sanitizedInput) : [];
  const selectedIds = details.selectedIds ?? [];
  return (
    <div className="trace-tool-diagnostics">
      <dl className="trace-event__tokens trace-event__tokens--five">
        <Metric label="Operation" value={details.operation ?? "-"} mono />
        <Metric label="Provider" value={details.provider ?? "-"} mono />
        <Metric label="Source policy" value={details.sourcePolicy ?? "-"} mono />
        <Metric label="Output chars" value={details.outputChars ?? "-"} />
        <Metric label="Result count" value={details.resultCount ?? "-"} />
      </dl>

      {sanitizedEntries.length > 0 && (
        <section className="trace-event__block">
          <span>脱敏输入</span>
          <dl className="trace-tool-diagnostics__input">
            {sanitizedEntries.map(([key, value]) => (
              <div key={key}>
                <dt>{key}</dt>
                <dd>{formatSafeValue(value)}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}

      {details.outputPreview && (
        <section className="trace-event__block trace-event__observation">
          <span>输出预览{details.outputTruncated ? "（已截断）" : ""}</span>
          <p>{details.outputPreview}</p>
        </section>
      )}

      <TraceContentBlock label="实际工具输入" content={details.rawInput} />
      <TraceContentBlock label="实际 Observe 内容" content={details.rawObservation} />
      <TraceJsonBlock label="执行背景与外部条件" value={details.attributes} />

      {(details.outputSha256 || details.inputSummary || details.observationSummary) && (
        <dl className="trace-tool-diagnostics__hashes">
          {details.outputSha256 && <Metric label="Output SHA-256" value={details.outputSha256} mono />}
          {details.inputSummary && <Metric label="旧版输入摘要" value={details.inputSummary} mono />}
          {details.observationSummary && (
            <Metric label="旧版输出摘要" value={details.observationSummary} mono />
          )}
        </dl>
      )}

      {selectedIds.length > 0 && (
        <section className="trace-event__block">
          <span>选中结果</span>
          <ul className="trace-tool-diagnostics__ids">
            {selectedIds.map((id) => <li key={id}>{id}</li>)}
          </ul>
        </section>
      )}
    </div>
  );
}

function TraceMetadataSection({ metadata }: { metadata: TraceMetadata }) {
  const componentEntries = metadata.components
    ? COMPONENT_FIELDS.flatMap(([key, label]) => {
      const value = metadata.components?.[key];
      return value ? [{ key, label, value }] : [];
    })
    : [];
  const retrievalStatuses = metadata.retrieval?.statuses ?? {};
  const retrievalEvidence = metadata.retrieval?.evidence ?? [];
  const effectiveTools = metadata.toolPlan?.effectiveTools ?? [];

  return (
    <section className="trace-metadata" aria-labelledby="trace-metadata-title">
      <header className="trace-section-heading">
        <div>
          <p className="admin-page__eyebrow">Administrator archive</p>
          <h2 id="trace-metadata-title">Trace 运行元数据</h2>
        </div>
        <p>管理员全链路档案；正文按显式上限保存，运行凭证仍会过滤。</p>
      </header>

      <dl className="trace-detail-summary">
        <SummaryItem label="运行模式" value={metadata.runMode ?? "-"} />
        <SummaryItem label="失败类型" value={metadata.failureType ?? "-"} mono />
        <SummaryItem label="结果原因" value={metadata.outcomeReason ?? "-"} mono />
        <SummaryItem
          label="LLM / Tool 调用"
          value={`${metadata.llmCallCount ?? "-"} / ${metadata.toolCallCount ?? "-"}`}
        />
        {componentEntries.map((component) => (
          <SummaryItem key={component.key} label={component.label} value={component.value} mono />
        ))}
        {metadata.skill && (
          <>
            <SummaryItem
              label="Skill"
              value={[metadata.skill.id, metadata.skill.version].filter(Boolean).join(" · ") || "-"}
              mono
            />
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
            <SummaryItem label="Evidence 快照" value={metadata.retrieval.evidenceSnapshotHash ?? "-"} mono />
            <SummaryItem
              label="检索降级"
              value={metadata.retrieval.degraded == null ? "-" : metadata.retrieval.degraded ? "是" : "否"}
            />
            <SummaryItem label="引用校验" value={metadata.retrieval.citationValidationStatus ?? "-"} />
          </>
        )}
        {metadata.turn && (
          <>
            <SummaryItem label="Turn ID" value={metadata.turn.turnId ?? "-"} mono />
            <SummaryItem label="Request ID" value={metadata.turn.requestId ?? "-"} mono />
            <SummaryItem
              label="会话 / 连接"
              value={`${metadata.turn.chatSessionId ?? "-"} / ${metadata.turn.connectionId ?? "-"}`}
              mono
            />
            <SummaryItem label="单轮预算" value={formatMs(metadata.turn.budgetMs)} />
            {metadata.turn.maxSteps != null && (
              <SummaryItem label="推理步数上限" value={`${metadata.turn.maxSteps} 步`} />
            )}
            <SummaryItem
              label="客户端终态"
              value={`${metadata.turn.clientDeliveryStatus ?? "-"} / ${metadata.turn.clientTerminalType ?? "-"}`}
              mono
            />
            <SummaryItem
              label="中止位置"
              value={metadata.turn.timeoutStage ?? (metadata.turn.cancelled ? "cancelled" : "-")}
              mono
            />
            {metadata.turn.clientDeliveryCode && (
              <SummaryItem label="交付异常" value={metadata.turn.clientDeliveryCode} mono />
            )}
          </>
        )}
        {metadata.memory && (
          <>
            <SummaryItem label="记忆写入" value={metadata.memory.writeStatus ?? "-"} mono />
            {metadata.memory.failureCode && (
              <SummaryItem label="记忆异常" value={metadata.memory.failureCode} mono />
            )}
          </>
        )}
        {metadata.toolPlan && (
          <>
            <SummaryItem label="工具规划" value={metadata.toolPlan.reason ?? "-"} mono />
            <SummaryItem
              label="本轮工具"
              value={effectiveTools.length > 0
                ? effectiveTools.join(", ")
                : "无"}
              mono
            />
          </>
        )}
        {metadata.answerTiming && (
          <SummaryItem
            label="模型 / 校验 / 可见"
            value={`${formatMs(metadata.answerTiming.modelFirstTokenMs)} / ${formatMs(metadata.answerTiming.safeAnswerReadyMs)} / ${formatMs(metadata.answerTiming.firstClientDeltaMs)}`}
          />
        )}
        {metadata.capture && (
          <>
            <SummaryItem label="采集级别" value={metadata.capture.level ?? "-"} mono />
            <SummaryItem label="采集策略" value={metadata.capture.policyVersion ?? "-"} mono />
            <SummaryItem
              label="正文采集"
              value={`${metadata.capture.capturedContentChars ?? "-"} / ${metadata.capture.maxTotalContentChars ?? "-"} chars`}
            />
            <SummaryItem
              label="截断 / 凭证过滤"
              value={`${metadata.capture.truncatedContentFields ?? 0} / ${metadata.capture.credentialRedactions ?? 0}`}
            />
          </>
        )}
        {metadata.completeness && (
          <>
            <SummaryItem
              label="事件丢弃 / 上限"
              value={`${metadata.completeness.droppedEvents ?? 0} / ${metadata.completeness.eventLimit ?? "-"}`}
            />
            <SummaryItem
              label="工具预览截断"
              value={metadata.completeness.truncatedToolOutputs ?? 0}
            />
          </>
        )}
      </dl>

      {metadata.completeness && metadata.completeness.complete === false && (
        <div className="admin-alert">
          Trace 事件不完整：已丢弃 {metadata.completeness.droppedEvents ?? 0} 个事件。
        </div>
      )}

      {metadata.input && (
        <section className="trace-turn-context" aria-label="本轮原始上下文">
          <header>
            <div>
              <p className="admin-page__eyebrow">Turn input</p>
              <h3>本轮原始问题与页面上下文</h3>
            </div>
            <span className="trace-mono">{metadata.input.fingerprint ?? "-"}</span>
          </header>
          <TraceContentBlock label="用户问题" content={metadata.input.question} />
          <TraceContentBlock label="页面上下文" content={metadata.input.pageContext} />
        </section>
      )}

      {metadata.steps && metadata.steps.length > 0 && (
        <div className="trace-metadata__cards" role="region" aria-label="Agent 循环步骤">
          {metadata.steps.map((step, index) => (
            <article className="trace-metadata-card" key={`${step.stepIndex}-${step.action}-${index}`}>
              <h3>Step {step.stepIndex + 1} · {step.action}</h3>
              <dl>
                <Metric label="LLM / Tool 耗时" value={`${formatMs(step.llmMs)} / ${formatMs(step.toolMs)}`} />
              </dl>
            </article>
          ))}
        </div>
      )}

      {metadata.retrieval && (Object.keys(retrievalStatuses).length > 0 || retrievalEvidence.length > 0) && (
        <div className="trace-metadata__cards">
          <article className="trace-metadata-card">
            <h3>检索路由</h3>
            <ul>
              {Object.entries(retrievalStatuses).map(([route, status]) => (
                <li key={route}>{route}: {status}</li>
              ))}
            </ul>
          </article>
          {retrievalEvidence.map((evidence, index) => (
            <article className="trace-metadata-card" key={`${evidence.citationId}-${index}`}>
              <h3>{evidence.citationId ?? "-"} · {evidence.documentId ?? "-"}</h3>
              <dl>
                <Metric label="来源" value={evidence.source ?? "-"} />
                <Metric label="来源版本" value={evidence.sourceVersion ?? "-"} mono />
                <Metric label="来源摘要" value={evidence.sourceHash ?? "-"} mono />
              </dl>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}

function TraceUnavailable({ compatibility }: { compatibility: TraceDetail["compatibility"] }) {
  const malformed = compatibility === "MALFORMED";
  return (
    <section className="trace-unavailable">
      <span aria-hidden="true">{malformed ? "!" : "?"}</span>
      <div>
        <h2>{malformed ? "这条 Trace 无法安全解析" : "这条 Trace 版本暂不受支持"}</h2>
        <p>这条记录无法按 v4 合同组织成可靠链路，请重新触发一次 Agent 任务生成新 Trace。</p>
      </div>
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

function Metric({
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

function resolveTimelineDuration(trace: TraceDetail) {
  const eventEnd = trace.phases
    .flatMap((phase) => phase.events)
    .reduce((latest, event) => {
      if (event.startedOffsetMs == null) return latest;
      return Math.max(latest, event.startedOffsetMs + (event.durationMs ?? 0));
    }, 0);
  return Math.max(1, trace.durationMs ?? 0, eventEnd);
}

function eventBarStyle(event: TraceEvent, exact: boolean, totalMs: number): CSSProperties | undefined {
  if (!exact || event.startedOffsetMs == null) return undefined;
  const left = Math.min(99, Math.max(0, event.startedOffsetMs / totalMs * 100));
  const durationPercent = Math.max(1.2, (event.durationMs ?? 0) / totalMs * 100);
  const width = Math.max(0.8, Math.min(durationPercent, 100 - left));
  return {
    "--trace-left": `${left}%`,
    "--trace-width": `${width}%`,
  } as CSSProperties;
}

function formatCompatibility(trace: TraceDetail) {
  if (trace.compatibility === "NATIVE_V4") return "原生 v4 · 精确时间";
  if (trace.compatibility === "LEGACY_V3") return "旧版 v3 · 仅调用耗时";
  if (trace.compatibility === "MALFORMED") return "载荷损坏 · 已隔离";
  return "版本不支持 · 已隔离";
}

function formatTimingAccuracy(value: TraceDetail["timingAccuracy"]) {
  if (value === "EXACT") return "精确";
  if (value === "DURATION_ONLY") return "仅调用耗时";
  return "不可用";
}

function phaseLabel(phase: string) {
  return PHASE_LABELS[phase] ?? phase;
}

function eventDisplayName(event: TraceEvent) {
  if (event.type === "llm") {
    const purpose = (event.details as TraceLlmDetails | null)?.purpose;
    return purpose || "模型调用";
  }
  return event.type === "tool" ? event.name : phaseLabel(event.phase);
}

function eventTypeLabel(type: string) {
  if (type === "llm") return "模型";
  if (type === "tool") return "工具";
  return "阶段";
}

function statusTone(status: string | null | undefined) {
  const normalized = (status || "UNKNOWN").toUpperCase();
  if (["SUCCESS", "ACCEPTED", "COMPLETE", "VALID", "DELIVERED"].includes(normalized)) return "ok";
  if (["TIMEOUT", "CANCELLED", "INTERRUPTED", "ABORTED", "DEGRADED"].includes(normalized)) return "warn";
  if (["ERROR", "FAILURE", "FAILED", "INVALID"].includes(normalized)) return "fail";
  return "neutral";
}

function formatSafeValue(value: unknown): string {
  if (value == null) return "-";
  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }
  if (Array.isArray(value)) return value.map(formatSafeValue).join(", ");
  return "[结构化值]";
}

function TraceContentBlock({
  label,
  content,
}: {
  label: string;
  content: TraceContent | null | undefined;
}) {
  if (!content) return null;
  return (
    <section className="trace-content-block">
      <header>
        <strong>{label}</strong>
        <div>
          {content.truncated && <span className="trace-content-block__warn">已截断</span>}
          {content.credentialRedacted && (
            <span className="trace-content-block__warn">凭证字段已过滤</span>
          )}
          <span>{content.sourceChars} chars</span>
        </div>
      </header>
      <pre>{content.text || "（空内容）"}</pre>
      {content.sha256 && (
        <footer>
          <span>SHA-256</span>
          <code>{content.sha256}</code>
        </footer>
      )}
    </section>
  );
}

function TraceJsonBlock({
  label,
  value,
}: {
  label: string;
  value: Record<string, unknown> | null | undefined;
}) {
  if (!value || Object.keys(value).length === 0) return null;
  return (
    <section className="trace-content-block">
      <header>
        <strong>{label}</strong>
        <div><span>{Object.keys(value).length} fields</span></div>
      </header>
      <pre>{JSON.stringify(value, null, 2)}</pre>
    </section>
  );
}

function formatMs(value: number | null | undefined) {
  if (value == null) return "-";
  return value < 1000 ? `${value}ms` : `${(value / 1000).toFixed(1)}s`;
}
