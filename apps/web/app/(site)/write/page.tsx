"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useMemo, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Button } from "@/components/ui/Button";
import { getAccessToken } from "@/lib/auth/tokens";
import { ApiError } from "@/lib/services/apiClient";
import { postService } from "@/lib/services/postService";
import { storageService } from "@/lib/services/storageService";
import { cn } from "@/lib/utils";
import { sha256Hex } from "@/lib/utils/sha256";

const PLACEHOLDERS = [
  "想写点什么呢……慢慢来，不着急。",
  "把想说的话写下来吧，我会安静地陪着你的。",
  "今天是想记录些什么吗？",
  "写给自己看的东西，不用太在意格式。",
  "有些想法，写下来就不会忘记了呢。",
];

const DRAFT_KEY = "chtholly-write-draft";

type SaveStatus = "saved" | "saving" | "unsaved";

type WriteDraft = {
  title: string;
  tags: string;
  description: string;
  markdown: string;
};

export default function WritePage() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [tags, setTags] = useState("");
  const [description, setDescription] = useState("");
  const [markdown, setMarkdown] = useState("");
  const [preview, setPreview] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [placeholderIndex, setPlaceholderIndex] = useState(0);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>("saved");
  const [draftHydrated, setDraftHydrated] = useState(false);

  const draft = useMemo<WriteDraft>(
    () => ({ title, tags, description, markdown }),
    [title, tags, description, markdown],
  );

  useEffect(() => {
    if (!getAccessToken()) {
      router.replace("/login");
    }
  }, [router]);

  useEffect(() => {
    try {
      const raw = window.localStorage.getItem(DRAFT_KEY);
      if (raw) {
        const saved = JSON.parse(raw) as Partial<WriteDraft>;
        setTitle(saved.title ?? "");
        setTags(saved.tags ?? "");
        setDescription(saved.description ?? "");
        setMarkdown(saved.markdown ?? "");
      }
    } catch {
      // 本地草稿损坏时直接忽略，避免影响写作入口。
    } finally {
      setDraftHydrated(true);
    }
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setPlaceholderIndex((prev) => (prev + 1) % PLACEHOLDERS.length);
    }, 8000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!draftHydrated) return undefined;
    setSaveStatus("unsaved");
    const timer = window.setTimeout(() => {
      setSaveStatus("saving");
      try {
        window.localStorage.setItem(DRAFT_KEY, JSON.stringify(draft));
        window.setTimeout(() => setSaveStatus("saved"), 180);
      } catch {
        setSaveStatus("unsaved");
      }
    }, 700);
    return () => window.clearTimeout(timer);
  }, [draft, draftHydrated]);

  const onPublish = async (e: FormEvent) => {
    e.preventDefault();
    if (!title.trim() || !markdown.trim()) {
      setError("标题和正文不能为空");
      return;
    }
    setLoading(true);
    setError("");
    try {
      const { id } = await postService.createDraft();
      const presign = await storageService.presign({
        scene: "post_content",
        postId: id,
        contentType: "text/markdown",
      });
      const etagRaw = await storageService.uploadPut(presign, markdown);
      const etag = etagRaw || (await sha256Hex(markdown)).slice(0, 32);
      const size = new TextEncoder().encode(markdown).length;
      const sha256 = await sha256Hex(markdown);
      await postService.confirmContent(id, {
        objectKey: presign.objectKey,
        etag,
        sha256,
        size,
      });
      const tagList = tags
        .split(/[,，]/)
        .map((t) => t.trim())
        .filter(Boolean)
        .slice(0, 20);
      await postService.patchMetadata(id, {
        title: title.trim(),
        tags: tagList,
        description: description.trim() || title.trim(),
        visible: "public",
      });
      await postService.publish(id);
      window.localStorage.removeItem(DRAFT_KEY);
      setSaveStatus("saved");
      router.push("/hub");
      router.refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "发布失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="write-container" data-testid="write-page">
      <form onSubmit={onPublish} className="write-editor-wrapper">
        <div className="write-status" aria-live="polite">
          {saveStatus === "saved" && <span>已保存</span>}
          {saveStatus === "saving" && <span>保存中...</span>}
          {saveStatus === "unsaved" && <span>有未保存的更改</span>}
        </div>

        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          className="write-title-input"
          placeholder="标题"
          aria-label="标题"
          required
        />

        <div className="write-meta-row">
          <input
            value={tags}
            onChange={(e) => setTags(e.target.value)}
            placeholder="标签，用逗号分隔"
            className="write-meta-input"
            aria-label="标签"
          />
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="一句话摘要"
            className="write-meta-input"
            aria-label="摘要"
          />
        </div>

        <div className="write-toolbar">
          <div className="write-mode-toggle" aria-label="编辑模式">
            <button
              type="button"
              className={cn("write-mode-btn", !preview && "write-mode-btn--active")}
              onClick={() => setPreview(false)}
            >
              编辑
            </button>
            <button
              type="button"
              className={cn("write-mode-btn", preview && "write-mode-btn--active")}
              onClick={() => setPreview(true)}
            >
              预览
            </button>
          </div>
          <Button type="submit" loading={loading} size="sm" data-testid="write-publish">
            发布
          </Button>
        </div>

        {preview ? (
          <div className="write-preview prose-anime">
            {markdown ? (
              <ReactMarkdown remarkPlugins={[remarkGfm]}>{markdown}</ReactMarkdown>
            ) : (
              <p className="write-preview-empty">还没有内容呢。</p>
            )}
          </div>
        ) : (
          <textarea
            className={cn("write-editor", "write-placeholder-fade")}
            placeholder={PLACEHOLDERS[placeholderIndex]}
            value={markdown}
            onChange={(e) => setMarkdown(e.target.value)}
            required
          />
        )}

        {error && <p className="write-error">{error}</p>}
      </form>
    </div>
  );
}
