import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { extractMarkdownHeadings } from "@/lib/markdownHeadings";

interface MarkdownContentProps {
  content: string;
}

/** OSS Markdown 正文渲染 */
export default function MarkdownContent({ content }: MarkdownContentProps) {
  const headings = extractMarkdownHeadings(content);
  const headingIdsByLine = new Map(headings.map((heading) => [heading.sourceLine, heading.id]));

  return (
    <div className="prose-anime pb-12">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          h2: ({ children, node }) => <h2 id={headingIdsByLine.get(node?.position?.start.line ?? -1)}>{children}</h2>,
          h3: ({ children, node }) => <h3 id={headingIdsByLine.get(node?.position?.start.line ?? -1)}>{children}</h3>,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}
