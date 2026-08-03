import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";

const safeComponents: Components = {
  img: ({ alt }) => {
    const label = alt?.trim();
    return (
      <span className="agent-markdown-image-placeholder" role="note">
        {label ? `[图片：${label}]` : "[图片已省略]"}
      </span>
    );
  },
};

export function SafeAgentMarkdown({ content }: { content: string }) {
  return (
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={safeComponents}>
      {content}
    </ReactMarkdown>
  );
}
