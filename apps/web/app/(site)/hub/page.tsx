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

type HubPageProps = {
  searchParams: Promise<{ page?: string }>;
};

const DEGRADED_HUB_FEED: HubFeedResponse = {
  latestPosts: [],
  latestPostsStatus: "degraded",
  latestPostsTotal: 0,
  hotTags: [],
  hotTagsStatus: "degraded",
  recommendations: [],
  recommendationsStatus: "degraded",
  experiences: [],
  experiencesStatus: "degraded",
};

export default async function HomePage({ searchParams }: HubPageProps) {
  const { page } = await searchParams;
  const currentPage = parsePositiveInt(page, 1);
  const pageSize = 8;

  let hubFeed = DEGRADED_HUB_FEED;
  try {
    hubFeed = await searchService.hubFeed(undefined, currentPage, pageSize);
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
    : deriveHotPosts(hubFeed.recommendations.length > 0 ? hubFeed.recommendations : hubFeed.latestPosts);
  const recommendations = hubFeed.recommendations.length > 0
    ? hubFeed.recommendations
    : hotPosts.slice(0, 5);

  return (
    <div className="hub-page">
      <div className="hub-timeline-layout">
        <main className="feed-ma">
          <HubDiscovery recommendations={recommendations} />

          <div className="hub-timeline-heading">
            <p>Timeline</p>
            <h2>仓库动态</h2>
          </div>
          <Suspense fallback={<PostCardSkeletonList count={3} />}>
            <HomeFeed
              items={hubFeed.latestPosts}
              status={hubFeed.latestPostsStatus}
              totalItems={hubFeed.latestPostsTotal}
              currentPage={currentPage}
              pageSize={pageSize}
            />
          </Suspense>
        </main>
        <Sidebar
          items={hubFeed.latestPosts}
          tags={hubFeed.hotTags}
          recommendations={hubFeed.recommendations}
          hotPosts={hotPosts}
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

function parsePositiveInt(value: string | undefined, fallback: number) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 1) return fallback;
  return parsed;
}

function deriveHotPosts(posts: FeedItem[]) {
  const byId = new Map<string, FeedItem>();
  posts.forEach((post) => byId.set(post.id, post));
  return [...byId.values()]
    .sort((a, b) => {
      const scoreA = (a.likeCount ?? 0) * 3 + (a.commentCount ?? 0) * 2 + (a.favoriteCount ?? 0);
      const scoreB = (b.likeCount ?? 0) * 3 + (b.commentCount ?? 0) * 2 + (b.favoriteCount ?? 0);
      return scoreB - scoreA;
    })
    .slice(0, 10);
}
