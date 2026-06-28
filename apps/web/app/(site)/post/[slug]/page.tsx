import Image from "next/image";
import Link from "next/link";
import { notFound } from "next/navigation";
import { Clock, Heart, Tag } from "lucide-react";
import MarkdownContent from "@/components/site/MarkdownContent";
import CommentSection from "@/components/site/CommentSection";
import Sidebar from "@/components/site/Sidebar";
import { Badge } from "@/components/ui/Badge";
import { postService } from "@/lib/services/postService";
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
    const res = await fetch(post.contentUrl, { next: { revalidate: 300 } });
    if (!res.ok) {
      throw new Error(`无法加载正文：${res.status}`);
    }
    markdown = await res.text();
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
              <div className="relative w-full aspect-[1038/576]">
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
                <span className="mr-4 inline-flex items-center gap-1">
                  <Clock size={13} className="inline" />
                  {formatDate(post.publishTime)}
                </span>
              )}
              <span className="mr-4">{post.authorNickname}</span>
              {post.likeCount > 0 && (
                <span className="inline-flex items-center gap-1">
                  <Heart size={13} className="inline" />
                  {post.likeCount}
                </span>
              )}
            </div>
          </div>

          <MarkdownContent content={markdown} />

          {post.tags.length > 0 && (
            <div className="px-[72px] pb-6 pt-5 border-t border-border max-md:px-6">
              <div className="flex flex-wrap items-center gap-2">
                <Tag size={14} className="text-text-secondary" />
                {post.tags.map((tag) => (
                  <Link
                    key={tag}
                    href={`/tag/${encodeURIComponent(tag)}`}
                    className="no-underline hover:opacity-80 transition-opacity duration-150"
                  >
                    <Badge className="bg-sky text-on-primary text-xs px-2.5 py-0.5">
                      {tag}
                    </Badge>
                  </Link>
                ))}
              </div>
            </div>
          )}
        </article>
        <CommentSection postId={post.id} />
      </div>
      <Sidebar />
    </div>
  );
}
