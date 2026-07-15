"use client";

import { FormEvent, useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import "../../styles/write.css";
import MarkdownToolbar from "@/components/write/MarkdownToolbar";
import TagAutocomplete from "@/components/write/TagAutocomplete";
import WriteSidebar from "@/components/write/WriteSidebar";
import { Button } from "@/components/ui/Button";
import { useRequireAuth } from "@/lib/hooks/useRequireAuth";
import { ApiError } from "@/lib/services/apiClient";
import { postAiService } from "@/lib/services/postAiService";
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
const IMAGE_MAX_BYTES = 5 * 1024 * 1024;
const IMAGE_TYPES = new Set(["image/png", "image/jpeg", "image/gif", "image/webp"]);

type SaveStatus = "saved" | "saving" | "unsaved";

type WriteDraft = {
  title: string;
  tags: string;
  description: string;
  markdown: string;
  draftPostId?: string | null;
};

const EMPTY_DRAFT: WriteDraft = {
  title: "",
  tags: "",
  description: "",
  markdown: "",
  draftPostId: null,
};

function subscribeBrowserReady() {
  return () => undefined;
}

function readDraft(): WriteDraft {
  try {
    const raw = window.localStorage.getItem(DRAFT_KEY);
    if (!raw) return EMPTY_DRAFT;
    const saved = JSON.parse(raw) as Partial<WriteDraft>;
    return {
      title: saved.title ?? "",
      tags: saved.tags ?? "",
      description: saved.description ?? "",
      markdown: saved.markdown ?? "",
      draftPostId: saved.draftPostId ?? null,
    };
  } catch {
    // 本地草稿损坏时直接忽略，避免影响写作入口。
    return EMPTY_DRAFT;
  }
}

export default function WritePage() {
  const authorized = useRequireAuth();
  const browserReady = useSyncExternalStore(subscribeBrowserReady, () => true, () => false);

  if (!authorized) return null;

  return (
    <WriteEditor
      key={browserReady ? "browser-draft" : "server-draft"}
      initialDraft={browserReady ? readDraft() : EMPTY_DRAFT}
    />
  );
}

function WriteEditor({ initialDraft }: { initialDraft: WriteDraft }) {
  const router = useRouter();
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [title, setTitle] = useState(initialDraft.title);
  const [tags, setTags] = useState(() => parseTags(initialDraft.tags));
  const [description, setDescription] = useState(initialDraft.description);
  const [markdown, setMarkdown] = useState(initialDraft.markdown);
  const [draftPostId, setDraftPostId] = useState<string | null>(initialDraft.draftPostId ?? null);
  const [preview, setPreview] = useState(false);
  const [loading, setLoading] = useState(false);
  const [uploadingImage, setUploadingImage] = useState(false);
  const [descriptionLoading, setDescriptionLoading] = useState(false);
  const [error, setError] = useState("");
  const [placeholderIndex, setPlaceholderIndex] = useState(0);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>("saved");
  const saveCompletionTimerRef = useRef<number | null>(null);

  const markDirty = () => {
    if (saveCompletionTimerRef.current !== null) {
      window.clearTimeout(saveCompletionTimerRef.current);
      saveCompletionTimerRef.current = null;
    }
    setSaveStatus("unsaved");
  };

  const draft = useMemo<WriteDraft>(
    () => ({
      title,
      tags: tags.join(","),
      description,
      markdown,
      draftPostId,
    }),
    [title, tags, description, markdown, draftPostId],
  );

  useEffect(() => {
    const timer = window.setInterval(() => {
      setPlaceholderIndex((prev) => (prev + 1) % PLACEHOLDERS.length);
    }, 8000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      setSaveStatus("saving");
      try {
        window.localStorage.setItem(DRAFT_KEY, JSON.stringify(draft));
        saveCompletionTimerRef.current = window.setTimeout(() => {
          saveCompletionTimerRef.current = null;
          setSaveStatus("saved");
        }, 180);
      } catch {
        setSaveStatus("unsaved");
      }
    }, 700);
    return () => {
      window.clearTimeout(timer);
      if (saveCompletionTimerRef.current !== null) {
        window.clearTimeout(saveCompletionTimerRef.current);
        saveCompletionTimerRef.current = null;
      }
    };
  }, [draft]);

  const ensureDraftPostId = async () => {
    if (draftPostId) return draftPostId;
    const { id } = await postService.createDraft();
    setDraftPostId(id);
    return id;
  };

  const onPublish = async (e: FormEvent) => {
    e.preventDefault();
    if (!title.trim() || !markdown.trim()) {
      setError("标题和正文不能为空");
      return;
    }
    setLoading(true);
    setError("");
    try {
      const id = await ensureDraftPostId();
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
      const tagList = tags.map((t) => t.trim()).filter(Boolean).slice(0, 20);
      await postService.patchMetadata(id, {
        title: title.trim(),
        tags: tagList,
        description: description.trim() || title.trim(),
        visible: "public",
      });
      await postService.publish(id);
      window.localStorage.removeItem(DRAFT_KEY);
      setDraftPostId(null);
      setSaveStatus("saved");
      router.push("/hub");
      router.refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "发布失败");
    } finally {
      setLoading(false);
    }
  };

  const onSuggestDescription = async () => {
    if (!markdown.trim()) {
      setError("先写一点正文吧，我需要读到内容才能帮你概括。");
      return;
    }
    setDescriptionLoading(true);
    setError("");
    try {
      const result = await postAiService.suggestDescription(markdown);
      setDescription(result.description);
      markDirty();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "AI 生成描述失败");
    } finally {
      setDescriptionLoading(false);
    }
  };

  const onImageUpload = async (file: File) => {
    if (!IMAGE_TYPES.has(file.type)) {
      setError("仅支持 PNG / JPEG / GIF / WebP 图片");
      return;
    }
    if (file.size > IMAGE_MAX_BYTES) {
      setError("图片不能超过 5MB");
      return;
    }

    setUploadingImage(true);
    setError("");
    try {
      const postId = await ensureDraftPostId();
      const ext = extensionForImage(file);
      const presign = await storageService.presign({
        scene: "post_image",
        postId,
        contentType: file.type,
        ext,
      });
      await storageService.uploadPut(presign, file);
      const publicUrl =
        presign.publicUrl ||
        (presign.method?.toUpperCase() === "POST"
          ? `/uploads/${presign.objectKey}`
          : stripQuery(presign.putUrl));
      const alt = file.name.replace(/\.[^.]+$/, "") || "图片";
      const el = textareaRef.current;
      const start = el?.selectionStart ?? markdown.length;
      const end = el?.selectionEnd ?? markdown.length;
      const inserted = `![${alt}](${publicUrl})`;
      const next = markdown.slice(0, start) + inserted + markdown.slice(end);
      setMarkdown(next);
      markDirty();
      requestAnimationFrame(() => {
        const target = textareaRef.current;
        if (!target) return;
        const cursor = start + inserted.length;
        target.focus();
        target.setSelectionRange(cursor, cursor);
      });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "图片上传失败");
    } finally {
      setUploadingImage(false);
    }
  };

  return (
    <div className="write-container" data-testid="write-page">
      <div className="write-workspace-layout" data-testid="write-workspace-layout">
      <form onSubmit={onPublish} className="write-editor-wrapper">
        <div className="write-status" aria-live="polite">
          {saveStatus === "saved" && <span>已保存</span>}
          {saveStatus === "saving" && <span>保存中...</span>}
          {saveStatus === "unsaved" && <span>有未保存的更改</span>}
        </div>

        <input
          value={title}
          onChange={(e) => {
            setTitle(e.target.value);
            markDirty();
          }}
          className="write-title-input"
          placeholder="标题"
          aria-label="标题"
          required
        />

        <div className="write-meta-row write-meta-row--tags">
          <TagAutocomplete
            value={tags}
            onChange={(nextTags) => {
              setTags(nextTags);
              markDirty();
            }}
          />
          <input
            value={description}
            onChange={(e) => {
              setDescription(e.target.value);
              markDirty();
            }}
            placeholder="一句话摘要"
            className="write-meta-input"
            aria-label="摘要"
          />
        </div>

        <div className="write-ai-description-row">
          <button
            type="button"
            className="write-ai-description-btn"
            onClick={() => void onSuggestDescription()}
            disabled={descriptionLoading || !markdown.trim()}
          >
            {descriptionLoading ? "生成中..." : "AI 生成描述"}
          </button>
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

        {!preview && (
          <MarkdownToolbar
            textareaRef={textareaRef}
            value={markdown}
            onChange={(nextMarkdown) => {
              setMarkdown(nextMarkdown);
              markDirty();
            }}
            onImageUpload={onImageUpload}
            uploading={uploadingImage}
            disabled={loading}
          />
        )}

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
            ref={textareaRef}
            className={cn("write-editor", "write-placeholder-fade")}
            placeholder={PLACEHOLDERS[placeholderIndex]}
            value={markdown}
            onChange={(e) => {
              setMarkdown(e.target.value);
              markDirty();
            }}
            required
          />
        )}

        {error && <p className="write-error">{error}</p>}
      </form>
      <WriteSidebar
        title={title}
        tags={tags}
        description={description}
        markdown={markdown}
        saveStatus={saveStatus}
      />
      </div>
    </div>
  );
}

function parseTags(raw: string) {
  return raw
    .split(/[,，]/)
    .map((tag) => tag.trim())
    .filter(Boolean)
    .slice(0, 20);
}

function extensionForImage(file: File) {
  switch (file.type) {
    case "image/png":
      return ".png";
    case "image/jpeg":
      return ".jpg";
    case "image/gif":
      return ".gif";
    case "image/webp":
      return ".webp";
    default:
      return undefined;
  }
}

function stripQuery(url: string) {
  try {
    const parsed = new URL(url);
    return `${parsed.origin}${parsed.pathname}`;
  } catch {
    return url.split("?")[0] ?? url;
  }
}
