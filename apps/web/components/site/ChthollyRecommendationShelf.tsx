import Link from "next/link";
import type { FeedItem } from "@/lib/types/post";

export default function ChthollyRecommendationShelf({ items }: { items: FeedItem[] }) {
  if (items.length === 0) {
    return <p className="room-muted">书架暂时空着。等有新文章再认真摆好。</p>;
  }

  return (
    <div className="room-recommendation-list">
      {items.slice(0, 4).map((item) => (
        <Link key={item.id} href={`/post/${item.slug}`} className="room-recommendation">
          <span>{item.title}</span>
          <small>{item.description || "她把这篇轻轻放在了书架上。"}</small>
        </Link>
      ))}
    </div>
  );
}
