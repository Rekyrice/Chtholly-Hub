import ChthollyObservation from "@/components/site/ChthollyObservation";
import ChthollyRecommendation from "@/components/site/ChthollyRecommendation";
import HotPosts from "@/components/site/HotPosts";
import type { FeedItem } from "@/lib/types/post";
import type { AgentExperienceItem } from "@/lib/types/search";

type HubDiscoveryProps = {
  recommendations: FeedItem[];
  hotPosts: FeedItem[];
  experiences: AgentExperienceItem[];
};

export default function HubDiscovery({
  recommendations,
  hotPosts,
  experiences,
}: HubDiscoveryProps) {
  return (
    <div className="hub-discovery">
      <ChthollyRecommendation posts={recommendations} />
      <div className="hub-discovery__secondary">
        <HotPosts posts={hotPosts} />
        <ChthollyObservation experiences={experiences} />
      </div>
    </div>
  );
}
