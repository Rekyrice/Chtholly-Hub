export type TopicCluster = {
  topicName: string;
  summary: string;
  size: number;
  keyEntities: string[];
  clusteredAt: string;
};

export type TopicPost = {
  id: string | number;
  title: string;
  description: string | null;
  publishTime: string | null;
  tags: string[];
};
