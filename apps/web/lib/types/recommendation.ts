/** 个性化推荐条目 */
export type RecommendedPostItem = {
  postId: number;
  title: string;
  score: number;
  reason: string;
};

/** GET /api/v1/recommendations 响应 */
export type RecommendationListResponse = {
  items: RecommendedPostItem[];
  personalized: boolean;
};
