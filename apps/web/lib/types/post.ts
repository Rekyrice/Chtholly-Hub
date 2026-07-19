/** Feed 单条帖子 */
export type FeedItem = {
  id: string;
  slug: string;
  title: string;
  description: string;
  coverImage?: string;
  tags: string[];
  tagJson?: string;
  authorId?: string;
  authorHandle?: string;
  authorAvatar?: string;
  authorNickname: string;
  status?: string;
  visible?: string;
  likeCount?: number;
  commentCount?: number;
  favoriteCount?: number;
  liked?: boolean;
  faved?: boolean;
  isTop?: boolean;
  publishTime?: string;
};

/** Feed 分页响应 */
export type FeedResponse = {
  items: FeedItem[];
  page: number;
  size: number;
  hasMore: boolean;
};

/** 帖子详情 */
export type PostDetailResponse = {
  id: string;
  slug: string;
  title: string;
  description: string;
  contentUrl: string;
  images: string[];
  tags: string[];
  authorId?: string;
  authorHandle?: string;
  authorAvatar?: string;
  authorNickname: string;
  authorBio?: string | null;
  authorTagJson?: string;
  likeCount: number;
  favoriteCount: number;
  liked?: boolean;
  faved?: boolean;
  isTop: boolean;
  visible: "public" | "followers" | "school" | "private" | "unlisted";
  type: string;
  publishTime?: string;
};

export type RelatedPostSummary = {
  id: string;
  slug?: string;
  title: string;
  summary?: string;
  description?: string;
  coverImage?: string;
  authorNickname?: string;
  sharedEntities?: string[];
};

export type PostSummary = RelatedPostSummary;

export type DraftEditPreviewStatus = "PENDING" | "APPLIED" | "REJECTED" | "EXPIRED";

export type DraftEditPreviewRequest = {
  baseContent: string;
  baseContentSha256: string;
  instruction: string;
};

export type DraftEditPreviewResponse = {
  previewId: string;
  draftId: string;
  skillId: "draft-edit";
  skillVersion: "v1";
  baseContentSha256: string;
  candidateContentSha256: string;
  previewHash: string;
  candidateContent: string;
  status: DraftEditPreviewStatus;
  expiresAt: string;
};

export type DraftEditDecisionResponse = {
  previewId: string;
  draftId: string;
  status: DraftEditPreviewStatus;
  contentSha256: string | null;
  contentUrl: string | null;
};
