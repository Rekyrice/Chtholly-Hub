/**
 * Markdown 插入结果：新正文 + 建议光标位置。
 */
export type MarkdownInsertResult = {
  value: string;
  selectionStart: number;
  selectionEnd: number;
};

/**
 * 在选区处插入/包裹 Markdown。有选中文本时包裹，否则插入模板并把光标放到占位处。
 */
export function applyMarkdownFormat(
  value: string,
  selectionStart: number,
  selectionEnd: number,
  before: string,
  after: string = "",
  placeholder: string = "",
): MarkdownInsertResult {
  const start = Math.max(0, Math.min(selectionStart, value.length));
  const end = Math.max(start, Math.min(selectionEnd, value.length));
  const selected = value.slice(start, end);

  if (selected.length > 0) {
    const next = value.slice(0, start) + before + selected + after + value.slice(end);
    const cursor = start + before.length + selected.length + after.length;
    return { value: next, selectionStart: cursor, selectionEnd: cursor };
  }

  const body = placeholder || "";
  const next = value.slice(0, start) + before + body + after + value.slice(end);
  const cursorStart = start + before.length;
  const cursorEnd = cursorStart + body.length;
  return { value: next, selectionStart: cursorStart, selectionEnd: cursorEnd };
}

/**
 * 在行首插入前缀（引用、列表等）。多行选区时对每行加前缀。
 */
export function applyLinePrefix(
  value: string,
  selectionStart: number,
  selectionEnd: number,
  prefix: string,
): MarkdownInsertResult {
  const start = Math.max(0, Math.min(selectionStart, value.length));
  const end = Math.max(start, Math.min(selectionEnd, value.length));

  const lineStart = value.lastIndexOf("\n", Math.max(0, start - 1)) + 1;
  const lineEndIndex = value.indexOf("\n", end);
  const lineEnd = lineEndIndex === -1 ? value.length : lineEndIndex;
  const block = value.slice(lineStart, lineEnd);
  const lines = block.length === 0 ? [""] : block.split("\n");
  const rewritten = lines
    .map((line, index) => {
      if (prefix === "1. ") {
        return `${index + 1}. ${line}`;
      }
      return `${prefix}${line}`;
    })
    .join("\n");

  const next = value.slice(0, lineStart) + rewritten + value.slice(lineEnd);
  const cursor = lineStart + rewritten.length;
  return { value: next, selectionStart: cursor, selectionEnd: cursor };
}

/**
 * 字数统计：中文字符按字计，英文/数字按词计。
 */
export function countWritingStats(markdown: string) {
  const text = markdown ?? "";
  const chinese = (text.match(/[\u4e00-\u9fff]/g) ?? []).length;
  const words = (text.match(/[A-Za-z0-9]+(?:'[A-Za-z0-9]+)?/g) ?? []).length;
  const charCount = chinese + words;
  const paragraphs = text
    .split(/\n\s*\n/)
    .map((part) => part.trim())
    .filter(Boolean).length;
  const readingMinutes = charCount === 0 ? 0 : Math.max(1, Math.ceil(charCount / 300));
  return { charCount, paragraphs, readingMinutes };
}
