export type TopicCluster = {
  topicName: string;
  summary: string;
  size: number;
  keyEntities: string[];
  clusteredAt: string;
};

export type TopicOverviewState = "READY" | "SPARSE" | "PENDING" | "FAILED";

export type TopicOverview = {
  items: TopicCluster[];
  state: TopicOverviewState;
  lastAttemptAt?: string | null;
  lastSuccessAt?: string | null;
  windowDays: number;
  reason?: string | null;
};

export type TopicPost = {
  id: string | number;
  title: string;
  description: string | null;
  publishTime: string | null;
  tags: string[];
};
