import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import type { Root } from "mdast";
import type { Plugin } from "unified";
import { extractMarkdownHeadings } from "@/lib/markdownHeadings";

interface MarkdownContentProps {
  content: string;
}

const remarkImageCaptions: Plugin<[], Root> = () => (tree) => {
  for (let index = 1; index < tree.children.length; index += 1) {
    const imageParagraph = tree.children[index - 1];
    const captionParagraph = tree.children[index];
    if (
      imageParagraph.type !== "paragraph"
      || imageParagraph.children.length !== 1
      || imageParagraph.children[0].type !== "image"
      || captionParagraph.type !== "paragraph"
      || captionParagraph.children.length !== 1
      || captionParagraph.children[0].type !== "emphasis"
    ) {
      continue;
    }

    captionParagraph.data = {
      ...captionParagraph.data,
      hProperties: {
        ...captionParagraph.data?.hProperties,
        className: ["article-image-caption"],
        "data-article-caption": "true",
      },
    };
  }
};

/** OSS Markdown 正文渲染 */
export default function MarkdownContent({ content }: MarkdownContentProps) {
  const headings = extractMarkdownHeadings(content);
  const headingIdsByLine = new Map(headings.map((heading) => [heading.sourceLine, heading.id]));

  return (
    <div className="prose-anime pb-12" data-article-body>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkImageCaptions]}
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
