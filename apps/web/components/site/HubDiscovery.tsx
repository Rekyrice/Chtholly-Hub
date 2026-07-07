import ChthollyRecommendation from "@/components/site/ChthollyRecommendation";
import type { FeedItem } from "@/lib/types/post";

type HubDiscoveryProps = {
  recommendations: FeedItem[];
};

export default function HubDiscovery({
  recommendations,
}: HubDiscoveryProps) {
  return (
    <div className="hub-discovery">
      <ChthollyRecommendation posts={recommendations} />
    </div>
  );
}
