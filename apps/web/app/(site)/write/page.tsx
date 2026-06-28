"use client";

import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Button } from "@/components/ui/Button";
import { getAccessToken } from "@/lib/auth/tokens";
import { ApiError } from "@/lib/services/apiClient";
import { postService } from "@/lib/services/postService";
import { storageService } from "@/lib/services/storageService";
import { cn } from "@/lib/utils";
import { sha256Hex } from "@/lib/utils/sha256";

export default function WritePage() {
  const router = useRouter();
  const [title, setTitle] = useState("");
  const [tags, setTags] = useState("");
  const [description, setDescription] = useState("");
  const [markdown, setMarkdown] = useState("");
  const [preview, setPreview] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!getAccessToken()) {
      router.replace("/login");
    }
  }, [router]);

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
      router.push("/");
      router.refresh();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "发布失败");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="post-card p-6 md:p-10 max-w-4xl mx-auto" data-testid="write-page">
      <h1 className="entry-title entry-title-single text-center mb-6">Write</h1>

      <form onSubmit={onPublish} className="space-y-4">
        <div>
          <label className="field-label">标题</label>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className="field-input"
            required
          />
        </div>

        <div>
          <label className="field-label">标签（逗号分隔）</label>
          <input
            value={tags}
            onChange={(e) => setTags(e.target.value)}
            placeholder="动漫, 追番"
            className="field-input"
          />
        </div>

        <div>
          <label className="field-label">摘要</label>
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="field-input"
          />
        </div>

        <div className="flex gap-2 mb-2">
          <Button
            type="button"
            size="sm"
            variant={!preview ? "primary" : "ghost"}
            onClick={() => setPreview(false)}
          >
            编辑
          </Button>
          <Button
            type="button"
            size="sm"
            variant={preview ? "primary" : "ghost"}
            onClick={() => setPreview(true)}
          >
            预览
          </Button>
        </div>

        {preview ? (
          <div className="prose-anime border border-border p-4 min-h-[240px] rounded-xl">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{markdown}</ReactMarkdown>
          </div>
        ) : (
          <textarea
            value={markdown}
            onChange={(e) => setMarkdown(e.target.value)}
            rows={16}
            placeholder="# 用 Markdown 写正文…"
            className={cn(
              "field-input font-mono text-[15px] leading-relaxed resize-y min-h-[240px]",
            )}
            required
          />
        )}

        {error && <p className="text-sm text-error">{error}</p>}

        <Button
          type="submit"
          loading={loading}
          size="lg"
          className="w-full tracking-wide"
          data-testid="write-publish"
        >
          发布
        </Button>
      </form>
    </div>
  );
}
