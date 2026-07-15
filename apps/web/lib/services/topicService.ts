import { apiFetch } from "./apiClient";
import type { TopicCluster, TopicPost } from "@/lib/types/topic";

const TOPIC_PREFIX = "/api/v1/topics";

export const topicService = {
  list: () => apiFetch<TopicCluster[]>(TOPIC_PREFIX),

  posts: (topicName: string) =>
    apiFetch<TopicPost[]>(`${TOPIC_PREFIX}/${encodeURIComponent(topicName)}/posts`),
};
