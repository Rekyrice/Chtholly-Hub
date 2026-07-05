import { Suspense } from "react";
import HomeFeed from "@/components/site/HomeFeed";
import { PostCardSkeletonList } from "@/components/site/PostCardSkeleton";
import Sidebar from "@/components/site/Sidebar";
import { searchService } from "@/lib/services/searchService";
import type { HubFeedResponse } from "@/lib/types/search";

export const revalidate = 60;

const DEGRADED_HUB_FEED: HubFeedResponse = {
  latestPosts: [],
  latestPostsStatus: "degraded",
  hotTags: [],
  hotTagsStatus: "degraded",
  recommendations: [],
  recommendationsStatus: "degraded",
  experiences: [],
  experiencesStatus: "degraded",
};

export default async function HomePage() {
  let hubFeed = DEGRADED_HUB_FEED;
  try {
    hubFeed = await searchService.hubFeed();
  } catch {
    hubFeed = DEGRADED_HUB_FEED;
  }

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[1fr_280px] gap-8 lg:items-start">
      <div className="feed-ma">
        <Suspense fallback={<PostCardSkeletonList count={3} />}>
          <HomeFeed items={hubFeed.latestPosts} status={hubFeed.latestPostsStatus} />
        </Suspense>
      </div>
      <Sidebar
        items={hubFeed.latestPosts}
        tags={hubFeed.hotTags}
        recommendations={hubFeed.recommendations}
        experiences={hubFeed.experiences}
        latestStatus={hubFeed.latestPostsStatus}
        tagsStatus={hubFeed.hotTagsStatus}
        recommendationsStatus={hubFeed.recommendationsStatus}
        experiencesStatus={hubFeed.experiencesStatus}
      />
    </div>
  );
}
