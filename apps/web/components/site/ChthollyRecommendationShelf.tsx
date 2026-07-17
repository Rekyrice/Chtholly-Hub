import Image from "next/image";
import Link from "next/link";
import type { FeedItem } from "@/lib/types/post";

export default function ChthollyRecommendationShelf({ items }: { items: FeedItem[] }) {
  if (items.length === 0) {
    return <p className="room-muted">书架暂时空着。等有新文章再认真摆好。</p>;
  }

  return (
    <div className="room-recommendation-list">
      {items.slice(0, 4).map((item) => (
        <Link key={item.id} href={`/post/${item.slug}`} className="room-book">
          <div className="room-book__cover" aria-hidden="true">
            {item.coverImage ? (
              <Image
                src={item.coverImage}
                alt=""
                fill
                sizes="(max-width: 560px) 96px, 132px"
              />
            ) : (
              <span>{item.tags?.[0] || "Chtholly Hub"}</span>
            )}
          </div>
          <div className="room-book__content">
            <div className="room-book__meta">
              <span>{item.authorNickname || "Chtholly Hub"}</span>
              {item.publishTime && <BookTime value={item.publishTime} />}
            </div>
            <h3>{item.title}</h3>
            <p>{item.description || "她把这篇轻轻放在了书架上。"}</p>
          </div>
        </Link>
      ))}
    </div>
  );
}

function BookTime({ value }: { value: string }) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return (
    <time dateTime={value}>
      {new Intl.DateTimeFormat("zh-CN", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
      }).format(date)}
    </time>
  );
}
