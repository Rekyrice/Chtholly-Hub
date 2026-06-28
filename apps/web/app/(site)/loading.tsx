import { PostCardSkeletonList } from "@/components/site/PostCardSkeleton";
import Sidebar from "@/components/site/Sidebar";

export default function SiteLoading() {
  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div className="feed-ma">
        <PostCardSkeletonList count={3} />
      </div>
      <Sidebar />
    </div>
  );
}
