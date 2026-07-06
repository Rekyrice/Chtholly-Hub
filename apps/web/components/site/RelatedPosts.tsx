import Image from "next/image";
import Link from "next/link";
import { postService } from "@/lib/services/postService";
import type { PostDetailResponse, RelatedPostSummary } from "@/lib/types/post";

type RelatedPostsProps = {
  postId: string;
};

type RelatedCard = RelatedPostSummary & {
  href?: string;
};

export default async function RelatedPosts({ postId }: RelatedPostsProps) {
  let related: RelatedPostSummary[] = [];
  try {
    related = await postService.related(postId);
  } catch {
    related = [];
  }

  if (related.length === 0) {
    return null;
  }

  const cards = await Promise.all(
    related.slice(0, 3).map(async (item): Promise<RelatedCard> => {
      try {
        const detail = await postService.detailById(item.id);
        return mergeRelatedDetail(item, detail);
      } catch {
        return {
          ...item,
          href: item.slug ? `/post/${item.slug}` : undefined,
        };
      }
    }),
  );

  return (
    <section className="related-posts" aria-labelledby="related-posts-title">
      <div className="article-section-heading">
        <p>继续读</p>
        <h2 id="related-posts-title">相关文章</h2>
      </div>
      <div className="related-posts__grid">
        {cards.map((post) => (
          <RelatedPostCard key={post.id} post={post} />
        ))}
      </div>
    </section>
  );
}

function mergeRelatedDetail(item: RelatedPostSummary, detail: PostDetailResponse): RelatedCard {
  return {
    ...item,
    slug: detail.slug,
    title: item.title || detail.title,
    description: item.description || detail.description,
    coverImage: detail.images?.[0],
    authorNickname: detail.authorNickname,
    href: `/post/${detail.slug}`,
  };
}

function RelatedPostCard({ post }: { post: RelatedCard }) {
  const body = post.summary || post.description || "这篇文章也提到了相近的线索。";
  const content = (
    <>
      <div className="related-post-card__image">
        {post.coverImage ? (
          <Image src={post.coverImage} alt="" fill sizes="(max-width: 768px) 100vw, 240px" />
        ) : (
          <span>{post.sharedEntities?.[0] ?? "Chtholly Hub"}</span>
        )}
      </div>
      <div className="related-post-card__body">
        <h3>{post.title}</h3>
        <p>{body}</p>
        <div className="related-post-card__meta">
          {post.authorNickname && <span>{post.authorNickname}</span>}
          {post.sharedEntities?.slice(0, 2).map((entity) => (
            <span key={entity}>{entity}</span>
          ))}
        </div>
      </div>
    </>
  );

  if (!post.href) {
    return <article className="related-post-card related-post-card--static">{content}</article>;
  }

  return (
    <Link href={post.href} className="related-post-card">
      {content}
    </Link>
  );
}
