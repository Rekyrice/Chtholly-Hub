import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

interface MarkdownContentProps {
  content: string;
}

/** OSS Markdown 正文渲染 */
export default function MarkdownContent({ content }: MarkdownContentProps) {
  return (
    <div className="prose-anime pb-12">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
    </div>
  );
}
