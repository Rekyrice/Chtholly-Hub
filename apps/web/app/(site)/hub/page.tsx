import { Suspense } from "react";
import HubDiscovery from "@/components/site/HubDiscovery";
import HomeFeed from "@/components/site/HomeFeed";
import { PostCardSkeletonList } from "@/components/site/PostCardSkeleton";
import Sidebar from "@/components/site/Sidebar";
import { agentService } from "@/lib/services/agentService";
import { searchService } from "@/lib/services/searchService";
import type { FeedItem } from "@/lib/types/post";
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

  let experiences = hubFeed.experiences;
  try {
    experiences = await agentService.recentExperiences(3);
  } catch {
    experiences = hubFeed.experiences;
  }

  const hotPosts = hubFeed.hotPosts?.length
    ? hubFeed.hotPosts
    : deriveHotPosts(hubFeed.latestPosts, hubFeed.recommendations);
  const recommendations = hubFeed.recommendations.length > 0
    ? hubFeed.recommendations
    : hotPosts.slice(0, 5);

  return (
    <div className="hub-page">
      <HubDiscovery
        recommendations={recommendations}
        hotPosts={hotPosts}
        experiences={experiences}
      />

      <div className="hub-timeline-layout">
        <main className="feed-ma">
          <div className="hub-timeline-heading">
            <p>Timeline</p>
            <h2>仓库动态</h2>
          </div>
        <Suspense fallback={<PostCardSkeletonList count={3} />}>
          <HomeFeed items={hubFeed.latestPosts} status={hubFeed.latestPostsStatus} />
        </Suspense>
        </main>
        <Sidebar
          items={hubFeed.latestPosts}
          tags={hubFeed.hotTags}
          recommendations={hubFeed.recommendations}
          experiences={experiences}
          latestStatus={hubFeed.latestPostsStatus}
          tagsStatus={hubFeed.hotTagsStatus}
          recommendationsStatus={hubFeed.recommendationsStatus}
          experiencesStatus={hubFeed.experiencesStatus}
        />
      </div>
    </div>
  );
}

function deriveHotPosts(latestPosts: FeedItem[], recommendations: FeedItem[]) {
  const byId = new Map<string, FeedItem>();
  [...latestPosts, ...recommendations].forEach((post) => byId.set(post.id, post));
  return [...byId.values()]
    .sort((a, b) => {
      const scoreA = (a.likeCount ?? 0) * 3 + (a.commentCount ?? 0) * 2 + (a.favoriteCount ?? 0);
      const scoreB = (b.likeCount ?? 0) * 3 + (b.commentCount ?? 0) * 2 + (b.favoriteCount ?? 0);
      return scoreB - scoreA;
    })
    .slice(0, 10);
}
