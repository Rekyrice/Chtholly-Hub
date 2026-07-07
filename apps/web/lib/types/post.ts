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
  authorAvatar?: string;
  authorNickname: string;
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
