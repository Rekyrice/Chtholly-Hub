export type MarkdownHeading = {
  level: 2 | 3;
  text: string;
  id: string;
  sourceLine: number;
};

function plainHeadingText(value: string) {
  return value
    .replace(/\[([^\]]+)\]\([^\)]+\)/g, "$1")
    .replace(/[*_~`]/g, "")
    .replace(/\s+#+\s*$/, "")
    .trim();
}

function headingId(value: string) {
  const normalized = value
    .normalize("NFKC")
    .toLowerCase()
    .replace(/[^\p{Letter}\p{Number}\s-]/gu, "")
    .trim()
    .replace(/[\s-]+/g, "-");
  return normalized || "section";
}

export function extractMarkdownHeadings(markdown: string): MarkdownHeading[] {
  const headings: MarkdownHeading[] = [];
  const occurrences = new Map<string, number>();
  let fence: "```" | "~~~" | null = null;

  for (const [lineIndex, line] of markdown.split(/\r?\n/).entries()) {
    const fenceMatch = line.match(/^\s*(```|~~~)/);
    if (fenceMatch) {
      const marker = fenceMatch[1] as "```" | "~~~";
      fence = fence === marker ? null : fence ?? marker;
      continue;
    }
    if (fence) continue;

    const match = line.match(/^\s*(#{2,3})\s+(.+?)\s*$/);
    if (!match) continue;

    const text = plainHeadingText(match[2]);
    if (!text) continue;
    const baseId = headingId(text);
    const count = (occurrences.get(baseId) ?? 0) + 1;
    occurrences.set(baseId, count);
    headings.push({
      level: match[1].length as 2 | 3,
      text,
      id: count === 1 ? baseId : `${baseId}-${count}`,
      sourceLine: lineIndex + 1,
    });
  }

  return headings;
}
