import { Suspense } from "react";
import HomeFeed from "@/components/site/HomeFeed";
import { PostCardSkeletonList } from "@/components/site/PostCardSkeleton";
import Sidebar from "@/components/site/Sidebar";

export const revalidate = 60;

export default function HomePage() {
  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div className="feed-ma">
        <Suspense fallback={<PostCardSkeletonList count={3} />}>
          <HomeFeed />
        </Suspense>
      </div>
      <Sidebar />
    </div>
  );
}
