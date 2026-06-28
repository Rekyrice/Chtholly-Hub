import { Skeleton } from "@/components/ui/Skeleton";

export function PostCardSkeleton() {
  return (
    <article className="post-card post-card-skeleton overflow-hidden" aria-hidden="true">
      <div className="post-card-image">
        <Skeleton width="100%" height={220} borderRadius={8} />
      </div>
      <div className="entry-header">
        <Skeleton width={72} height={22} pill className="mx-auto mb-3" />
        <Skeleton width="70%" height={28} className="mx-auto mb-3" />
        <Skeleton width={140} height={14} className="mx-auto" />
      </div>
      <div className="entry-summary">
        <Skeleton width="100%" height={14} className="mb-2" />
        <Skeleton width="92%" height={14} className="mb-2" />
        <Skeleton width="80%" height={14} />
      </div>
    </article>
  );
}

export function PostCardSkeletonList({ count = 3 }: { count?: number }) {
  return (
    <>
      {Array.from({ length: count }, (_, i) => (
        <PostCardSkeleton key={i} />
      ))}
    </>
  );
}
