import Image from "next/image";
import Link from "next/link";
import { PenLine } from "lucide-react";
import FollowButton from "@/components/site/FollowButton";
import PostOwnerActions from "@/components/site/PostOwnerActions";

type AuthorCardProps = {
  authorId?: string;
  authorHandle?: string;
  avatar?: string;
  nickname: string;
  bio?: string | null;
  postId?: string;
  postTitle?: string;
  postTop?: boolean;
  postVisibility?: string | null;
};

export default function AuthorCard({
  authorId,
  authorHandle,
  avatar,
  nickname,
  bio,
  postId,
  postTitle,
  postTop,
  postVisibility,
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
          <div className="author-card__actions">
            {postId && (
              <PostOwnerActions
                postId={postId}
                authorId={authorId}
                title={postTitle}
                initialTop={postTop}
                initialVisibility={postVisibility}
                redirectAfterDelete="/hub"
                compact
              />
            )}
            {authorId && <FollowButton userId={authorId} size="sm" showCounter={false} />}
          </div>
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
