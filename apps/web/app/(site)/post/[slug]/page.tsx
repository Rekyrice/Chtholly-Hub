import Image from "next/image";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Clock, Heart, Tag } from "lucide-react";
import MarkdownContent from "@/components/site/MarkdownContent";
import Sidebar from "@/components/site/Sidebar";
import { postService } from "@/lib/services/postService";
import { siteConfig } from "@/lib/site.config";
import { formatDate } from "@/lib/utils";

interface Props {
  params: Promise<{ slug: string }>;
}

export const revalidate = 60;

export async function generateMetadata({ params }: Props) {
  const { slug } = await params;
  try {
    const post = await postService.detailBySlug(slug);
    return {
      title: post.title,
      description: post.description,
    };
  } catch {
    return { title: "文章不存在" };
  }
}

async function fetchMarkdown(contentUrl: string): Promise<string> {
  const res = await fetch(contentUrl, { next: { revalidate: 300 } });
  if (!res.ok) {
    throw new Error(`无法加载正文：${res.status}`);
  }
  return res.text();
}

export default async function PostPage({ params }: Props) {
  const { slug } = await params;

  let post;
  try {
    post = await postService.detailBySlug(slug);
  } catch {
    notFound();
  }

  let markdown = "";
  try {
    markdown = await fetchMarkdown(post.contentUrl);
  } catch {
    markdown = "*正文暂时无法加载，请稍后再试。*";
  }

  const cover = post.images?.[0];

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div>
        <article className="post-card">
          {cover && (
            <div className="post-card-image">
              <div
                style={{
                  position: "relative",
                  width: "100%",
                  aspectRatio: "1038/576",
                }}
              >
                <Image
                  src={cover}
                  alt={post.title}
                  fill
                  className="object-cover"
                  priority
                  sizes="(max-width:1024px) 100vw, 780px"
                />
              </div>
            </div>
          )}

          <div className="entry-header">
            <h1 className="entry-title entry-title-single">{post.title}</h1>
            <div className="entry-meta">
              {post.publishTime && (
                <span style={{ marginRight: 16 }}>
                  <Clock
                    size={13}
                    style={{
                      display: "inline",
                      marginRight: 4,
                      verticalAlign: "middle",
                    }}
                  />
                  {formatDate(post.publishTime)}
                </span>
              )}
              <span style={{ marginRight: 16 }}>{post.authorNickname}</span>
              {post.likeCount > 0 && (
                <span>
                  <Heart
                    size={13}
                    style={{
                      display: "inline",
                      marginRight: 3,
                      verticalAlign: "middle",
                    }}
                  />
                  {post.likeCount}
                </span>
              )}
            </div>
          </div>

          <MarkdownContent content={markdown} />

          {post.tags.length > 0 && (
            <div
              style={{
                padding: "0 72px 24px",
                borderTop: "1px solid #f0f0f0",
                paddingTop: 20,
              }}
            >
              <div
                style={{
                  display: "flex",
                  flexWrap: "wrap",
                  alignItems: "center",
                  gap: 8,
                }}
              >
                <Tag size={14} style={{ color: "#9e9e9e" }} />
                {post.tags.map((tag) => (
                  <Link
                    key={tag}
                    href={`/tag/${encodeURIComponent(tag)}`}
                    style={{
                      padding: "3px 10px",
                      fontSize: 12,
                      color: "#fff",
                      backgroundColor: siteConfig.theme.primary,
                      textDecoration: "none",
                    }}
                    className="hover:opacity-80 transition-opacity"
                  >
                    {tag}
                  </Link>
                ))}
              </div>
            </div>
          )}
        </article>
      </div>
      <Sidebar />
    </div>
  );
}
