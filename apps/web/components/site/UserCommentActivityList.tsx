import Link from "next/link";
import type { UserCommentActivityItem } from "@/lib/types/comment";

type UserCommentActivityListProps = {
  items: UserCommentActivityItem[];
  error?: string | null;
  emptyTitle?: string;
  onRetry?: () => void;
  retrying?: boolean;
};

const activityDateFormatter = new Intl.DateTimeFormat("zh-CN-u-ca-gregory", {
  dateStyle: "long",
  timeStyle: "short",
  timeZone: "Asia/Shanghai",
});

export default function UserCommentActivityList({
  items,
  error,
  emptyTitle = "还没有留下公开回应",
  onRetry,
  retrying = false,
}: UserCommentActivityListProps) {
  return (
    <div className="member-comment-activity" aria-busy={retrying}>
      {items.length > 0 ? (
        <ol className="member-comment-activity__list">
          {items.map((item) => (
            <li className="member-comment-activity__item" key={item.id}>
              <article>
                <div className="member-comment-activity__meta">
                  <Link href={`/post/${encodeURIComponent(item.postSlug)}`}>
                    {item.parentId ? "回复了" : "评论了"}《{item.postTitle}》
                  </Link>
                  <ActivityTime value={item.createdAt} />
                </div>
                <p className="member-comment-activity__content">{item.content}</p>
              </article>
            </li>
          ))}
        </ol>
      ) : !error ? (
        <div className="member-comment-activity__empty">
          <p>{emptyTitle}</p>
        </div>
      ) : null}

      {error && (
        <div className="member-comment-activity__error" role="alert">
          <p>{error}</p>
          {onRetry && (
            <button type="button" onClick={onRetry} disabled={retrying}>
              {retrying ? "正在重试…" : "重新加载"}
            </button>
          )}
        </div>
      )}
    </div>
  );
}

function ActivityTime({ value }: { value: string }) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return <span>时间未公开</span>;
  }

  let label: string;
  try {
    label = activityDateFormatter.format(date);
  } catch {
    label = date.toISOString().replace("T", " ").slice(0, 16) + " UTC";
  }

  return (
    <time role="time" dateTime={date.toISOString()}>
      {label}
    </time>
  );
}
