import Image from "next/image";
import Link from "next/link";
import { PenLine } from "lucide-react";
import FollowButton from "@/components/site/FollowButton";

type AuthorCardProps = {
  authorId?: string;
  authorHandle?: string;
  avatar?: string;
  nickname: string;
  bio?: string | null;
};

export default function AuthorCard({
  authorId,
  authorHandle,
  avatar,
  nickname,
  bio,
}: AuthorCardProps) {
  const postsHref = authorHandle
    ? `/user/${encodeURIComponent(authorHandle)}`
    : authorId
      ? `/hub?ownerId=${encodeURIComponent(authorId)}`
      : "/hub";

  return (
    <section className="author-card" aria-label="作者信息">
      <div className="author-card__avatar" aria-hidden={!avatar}>
        {avatar ? (
          <Image src={avatar} alt={nickname} width={72} height={72} />
        ) : (
          <span>{nickname.slice(0, 1).toUpperCase()}</span>
        )}
      </div>
      <div className="author-card__body">
        <div className="author-card__header">
          <div>
            <p className="author-card__eyebrow">作者</p>
            <h2>{nickname}</h2>
          </div>
          {authorId && <FollowButton userId={authorId} size="sm" showCounter={false} />}
        </div>
        <p className="author-card__bio">
          {bio || "这个人还没有写简介，不过已经在仓库里留下了一些故事。"}
        </p>
        <Link href={postsHref} className="author-card__link">
          <PenLine size={15} />
          <span>查看作者所有文章</span>
        </Link>
      </div>
    </section>
  );
}
