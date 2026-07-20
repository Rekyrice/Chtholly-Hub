"use client";

import { useRef, useState } from "react";
import { CheckCircle2, FileDiff, ShieldCheck, XCircle } from "lucide-react";
import { ApiError } from "@/lib/services/apiClient";
import { postService } from "@/lib/services/postService";
import type { DraftEditPreviewResponse } from "@/lib/types/post";
import { sha256Hex } from "@/lib/utils/sha256";

type DraftEditPanelProps = {
  markdown: string;
  ensureDraftContent: (exactMarkdown: string) => Promise<{ draftId: string; sha256: string }>;
  onApply: (candidateContent: string) => void;
  disabled?: boolean;
};

type BusyAction = "preview" | "confirm" | "reject" | null;

/** Dedicated UI for draft-edit@v1; it never enters the general Agent tool loop. */
export default function DraftEditPanel({
  markdown,
  ensureDraftContent,
  onApply,
  disabled = false,
}: DraftEditPanelProps) {
  const latestMarkdownRef = useRef(markdown);
  latestMarkdownRef.current = markdown;
  const [instruction, setInstruction] = useState("");
  const [preview, setPreview] = useState<DraftEditPreviewResponse | null>(null);
  const [busy, setBusy] = useState<BusyAction>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const createPreview = async () => {
    const exactMarkdown = latestMarkdownRef.current;
    if (!exactMarkdown.trim() || !instruction.trim()) {
      setError("请先填写正文和编辑要求。");
      return;
    }
    setBusy("preview");
    setError("");
    setNotice("");
    try {
      const synchronized = await ensureDraftContent(exactMarkdown);
      const result = await postService.createDraftEditPreview(synchronized.draftId, {
        baseContent: exactMarkdown,
        baseContentSha256: synchronized.sha256,
        instruction: instruction.trim(),
      });
      setPreview(result);
      setNotice("候选已生成，确认前仍可继续编辑原文。");
    } catch (cause) {
      setError(messageOf(cause, "生成受控预览失败。"));
    } finally {
      setBusy(null);
    }
  };

  const confirmPreview = async () => {
    if (!preview || preview.status !== "PENDING") return;
    const editorSnapshot = latestMarkdownRef.current;
    setBusy("confirm");
    setError("");
    setNotice("");
    try {
      const currentSha256 = await sha256Hex(editorSnapshot);
      if (
        latestMarkdownRef.current !== editorSnapshot ||
        currentSha256 !== preview.baseContentSha256
      ) {
        setError("正文已变化，请重新生成预览。");
        return;
      }
      const result = await postService.confirmDraftEditPreview(
        preview.draftId,
        preview.previewId,
        preview.previewHash,
      );
      if (
        result.status !== "APPLIED" ||
        result.contentSha256 !== preview.candidateContentSha256
      ) {
        setError("服务端候选完整性校验失败，原文未替换。");
        return;
      }
      onApply(preview.candidateContent);
      setPreview({ ...preview, status: "APPLIED" });
      setNotice("候选已写入草稿。");
    } catch (cause) {
      setError(messageOf(cause, "确认草稿候选失败。"));
    } finally {
      setBusy(null);
    }
  };

  const rejectPreview = async () => {
    if (!preview || preview.status !== "PENDING") return;
    setBusy("reject");
    setError("");
    setNotice("");
    try {
      const result = await postService.rejectDraftEditPreview(
        preview.draftId,
        preview.previewId,
        preview.previewHash,
      );
      if (result.status !== "REJECTED") {
        setError("预览状态已变化，请重新生成候选。");
        return;
      }
      setPreview({ ...preview, status: "REJECTED" });
      setNotice("候选已拒绝，原草稿未改变。");
    } catch (cause) {
      setError(messageOf(cause, "拒绝草稿候选失败。"));
    } finally {
      setBusy(null);
    }
  };

  const unavailable = disabled || busy !== null;

  return (
    <section className="write-sidebar__card draft-edit-panel" aria-labelledby="draft-edit-title">
      <header className="draft-edit-panel__header">
        <span className="draft-edit-panel__icon" aria-hidden="true"><FileDiff size={17} /></span>
        <div>
          <p className="draft-edit-panel__eyebrow">受控校样</p>
          <h2 id="draft-edit-title">草稿编辑预览</h2>
        </div>
        <span className="draft-edit-panel__version">draft-edit@v1</span>
      </header>

      <p className="draft-edit-panel__contract">
        <ShieldCheck size={14} aria-hidden="true" />
        只生成候选；确认后才写入当前草稿，不会发布。
      </p>

      <label className="draft-edit-panel__field">
        <span>编辑要求</span>
        <textarea
          value={instruction}
          onChange={(event) => setInstruction(event.target.value)}
          placeholder="例如：压缩重复段落，保留事实与语气"
          maxLength={2000}
          disabled={unavailable}
        />
      </label>

      <button
        type="button"
        className="draft-edit-panel__generate"
        onClick={() => void createPreview()}
        disabled={unavailable || !markdown.trim() || !instruction.trim()}
      >
        {busy === "preview" ? "同步并生成中…" : "生成受控预览"}
      </button>

      {preview && (
        <div className="draft-edit-panel__preview">
          <div className="draft-edit-panel__preview-heading">
            <span>候选 Markdown</span>
            <small>{preview.status}</small>
          </div>
          <pre>{preview.candidateContent}</pre>
          <p className="draft-edit-panel__expiry">
            预览有效至 {formatExpiry(preview.expiresAt)}
          </p>
          {preview.status === "PENDING" && (
            <div className="draft-edit-panel__actions">
              <button
                type="button"
                className="draft-edit-panel__confirm"
                onClick={() => void confirmPreview()}
                disabled={unavailable}
              >
                <CheckCircle2 size={15} aria-hidden="true" />
                {busy === "confirm" ? "确认中…" : "确认写入草稿"}
              </button>
              <button
                type="button"
                className="draft-edit-panel__reject"
                onClick={() => void rejectPreview()}
                disabled={unavailable}
              >
                <XCircle size={15} aria-hidden="true" />
                {busy === "reject" ? "拒绝中…" : "拒绝此候选"}
              </button>
            </div>
          )}
        </div>
      )}

      <div className="draft-edit-panel__message" aria-live="polite">
        {error && <p className="draft-edit-panel__error">{error}</p>}
        {!error && notice && <p className="draft-edit-panel__notice">{notice}</p>}
      </div>
    </section>
  );
}

function messageOf(cause: unknown, fallback: string) {
  return cause instanceof ApiError ? cause.message : fallback;
}

function formatExpiry(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "未知";
  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}
