import FavButton from "@/components/site/FavButton";
import LikeButton from "@/components/site/LikeButton";
import ShareButton from "@/components/site/ShareButton";

type ArticleActionsProps = {
  postId: string;
  slug: string;
  title: string;
  initialLiked?: boolean;
  initialFaved?: boolean;
  initialLikeCount?: number;
  initialFavCount?: number;
};

export default function ArticleActions({
  postId,
  slug,
  title,
  initialLiked,
  initialFaved,
  initialLikeCount = 0,
  initialFavCount = 0,
}: ArticleActionsProps) {
  const shareHref = `/post/${slug}`;

  return (
    <div className="article-action-bar" aria-label="文章操作">
      <LikeButton
        entityType="post"
        entityId={postId}
        initialLiked={initialLiked}
        initialCount={initialLikeCount}
      />
      <FavButton
        entityType="post"
        entityId={postId}
        initialFaved={initialFaved}
        initialCount={initialFavCount}
      />
      <ShareButton href={shareHref} title={title} />
    </div>
  );
}
