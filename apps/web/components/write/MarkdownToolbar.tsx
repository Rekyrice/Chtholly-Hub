"use client";

import {
  Bold,
  ChevronDown,
  Code2,
  Heading2,
  ImagePlus,
  Italic,
  Link2,
  List,
  ListOrdered,
  Minus,
  Quote,
} from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { cn } from "@/lib/utils";
import {
  applyLinePrefix,
  applyMarkdownFormat,
  type MarkdownInsertResult,
} from "@/lib/utils/markdownInsert";

type MarkdownToolbarProps = {
  textareaRef: React.RefObject<HTMLTextAreaElement | null>;
  value: string;
  onChange: (next: string) => void;
  onImageUpload: (file: File) => Promise<void>;
  uploading?: boolean;
  disabled?: boolean;
};

type FormatAction =
  | { kind: "wrap"; before: string; after?: string; placeholder?: string }
  | { kind: "line"; prefix: string }
  | { kind: "insert"; before: string; after?: string; placeholder?: string };

export default function MarkdownToolbar({
  textareaRef,
  value,
  onChange,
  onImageUpload,
  uploading = false,
  disabled = false,
}: MarkdownToolbarProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [expanded, setExpanded] = useState(false);
  const [headingOpen, setHeadingOpen] = useState(false);

  useEffect(() => {
    const onResize = () => {
      if (window.innerWidth >= 768) setExpanded(true);
    };
    onResize();
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  const applyResult = (result: MarkdownInsertResult) => {
    onChange(result.value);
    requestAnimationFrame(() => {
      const el = textareaRef.current;
      if (!el) return;
      el.focus();
      el.setSelectionRange(result.selectionStart, result.selectionEnd);
    });
  };

  const run = (action: FormatAction) => {
    const el = textareaRef.current;
    const start = el?.selectionStart ?? value.length;
    const end = el?.selectionEnd ?? value.length;
    if (action.kind === "line") {
      applyResult(applyLinePrefix(value, start, end, action.prefix));
      return;
    }
    applyResult(
      applyMarkdownFormat(
        value,
        start,
        end,
        action.before,
        action.after ?? "",
        action.placeholder ?? "",
      ),
    );
  };

  const onPickImage = () => {
    if (disabled || uploading) return;
    fileInputRef.current?.click();
  };

  const onFileChange = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    await onImageUpload(file);
  };

  return (
    <div className={cn("md-toolbar", expanded && "md-toolbar--expanded")}>
      <div className="md-toolbar__bar">
        <button
          type="button"
          className="md-toolbar__toggle md:hidden"
          onClick={() => setExpanded((prev) => !prev)}
          aria-expanded={expanded}
        >
          格式
          <ChevronDown size={14} className={cn(expanded && "md-toolbar__chevron--open")} />
        </button>

        <div className="md-toolbar__actions">
          <div className="md-toolbar__heading">
            <button
              type="button"
              className="md-toolbar__btn"
              disabled={disabled}
              aria-label="标题"
              onClick={() => setHeadingOpen((prev) => !prev)}
            >
              <Heading2 size={16} />
              <ChevronDown size={12} />
            </button>
            {headingOpen && (
              <div className="md-toolbar__menu" role="menu">
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setHeadingOpen(false);
                    run({ kind: "line", prefix: "## " });
                  }}
                >
                  二级标题
                </button>
                <button
                  type="button"
                  role="menuitem"
                  onClick={() => {
                    setHeadingOpen(false);
                    run({ kind: "line", prefix: "### " });
                  }}
                >
                  三级标题
                </button>
              </div>
            )}
          </div>

          <ToolbarButton
            label="粗体"
            disabled={disabled}
            onClick={() => run({ kind: "wrap", before: "**", after: "**", placeholder: "粗体文本" })}
          >
            <Bold size={16} />
          </ToolbarButton>
          <ToolbarButton
            label="斜体"
            disabled={disabled}
            onClick={() => run({ kind: "wrap", before: "*", after: "*", placeholder: "斜体文本" })}
          >
            <Italic size={16} />
          </ToolbarButton>
          <ToolbarButton
            label="引用"
            disabled={disabled}
            onClick={() => run({ kind: "line", prefix: "> " })}
          >
            <Quote size={16} />
          </ToolbarButton>
          <ToolbarButton
            label="代码块"
            disabled={disabled}
            onClick={() =>
              run({
                kind: "wrap",
                before: "```\n",
                after: "\n```",
                placeholder: "code",
              })
            }
          >
            <Code2 size={16} />
          </ToolbarButton>
          <ToolbarButton
            label="无序列表"
            disabled={disabled}
            onClick={() => run({ kind: "line", prefix: "- " })}
          >
            <List size={16} />
          </ToolbarButton>
          <ToolbarButton
            label="有序列表"
            disabled={disabled}
            onClick={() => run({ kind: "line", prefix: "1. " })}
          >
            <ListOrdered size={16} />
          </ToolbarButton>
          <ToolbarButton
            label="链接"
            disabled={disabled}
            onClick={() =>
              run({
                kind: "wrap",
                before: "[",
                after: "](https://)",
                placeholder: "链接文字",
              })
            }
          >
            <Link2 size={16} />
          </ToolbarButton>
          <ToolbarButton
            label={uploading ? "上传中..." : "图片"}
            disabled={disabled || uploading}
            onClick={onPickImage}
          >
            <ImagePlus size={16} />
          </ToolbarButton>
          <ToolbarButton
            label="分割线"
            disabled={disabled}
            onClick={() =>
              run({
                kind: "insert",
                before: "\n\n---\n\n",
                placeholder: "",
              })
            }
          >
            <Minus size={16} />
          </ToolbarButton>
        </div>

        {uploading && <span className="md-toolbar__status">上传中...</span>}
      </div>

      <input
        ref={fileInputRef}
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp"
        className="sr-only"
        onChange={(event) => void onFileChange(event)}
      />
    </div>
  );
}

function ToolbarButton({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string;
  disabled?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      className="md-toolbar__btn"
      title={label}
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
    >
      {children}
    </button>
  );
}
