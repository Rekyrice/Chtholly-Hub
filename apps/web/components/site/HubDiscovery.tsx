import HubPersonalizedRecommendations from "@/components/site/HubPersonalizedRecommendations";
import type { FeedItem } from "@/lib/types/post";

type HubDiscoveryProps = {
  recommendations: FeedItem[];
};

export default function HubDiscovery({ recommendations }: HubDiscoveryProps) {
  return (
    <div className="hub-discovery">
      <HubPersonalizedRecommendations fallback={recommendations} />
    </div>
  );
}
