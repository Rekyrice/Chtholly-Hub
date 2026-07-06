import Link from "next/link";
import { TrendingUp } from "lucide-react";
import type { FeedItem } from "@/lib/types/post";

type HotPostsProps = {
  posts: FeedItem[];
};

export default function HotPosts({ posts }: HotPostsProps) {
  const hotPosts = posts
    .slice()
    .sort((a, b) => (b.likeCount ?? 0) - (a.likeCount ?? 0))
    .slice(0, 8);

  return (
    <section className="hub-hot-posts">
      <div className="hub-section-heading">
        <p>Trending</p>
        <h2>
          <TrendingUp size={18} />
          热门文章
        </h2>
      </div>

      {hotPosts.length === 0 ? (
        <p className="hub-discovery-empty">还没有足够的热度数据。</p>
      ) : (
        <ol className="hub-hot-posts__list">
          {hotPosts.map((post, index) => (
            <li key={post.id}>
              <Link href={`/post/${post.slug}`}>
                <span className="hub-hot-posts__rank">{index + 1}</span>
                <span className="hub-hot-posts__body">
                  <strong>{post.title}</strong>
                  <small>{post.authorNickname || "仓库居民"} · {post.likeCount ?? 0} 赞</small>
                </span>
              </Link>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
