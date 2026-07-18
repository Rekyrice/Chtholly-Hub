import { apiFetch } from "./apiClient";
import type { TopicCluster, TopicOverview, TopicPost } from "@/lib/types/topic";

const TOPIC_PREFIX = "/api/v1/topics";

export const topicService = {
  list: () => apiFetch<TopicCluster[]>(TOPIC_PREFIX),

  overview: () => apiFetch<TopicOverview>(`${TOPIC_PREFIX}/overview`),

  posts: (topicName: string) =>
    apiFetch<TopicPost[]>(`${TOPIC_PREFIX}/${encodeURIComponent(topicName)}/posts`),
};
