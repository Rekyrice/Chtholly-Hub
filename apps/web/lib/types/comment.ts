export type CommentItem = {
  id: string;
  postId: string;
  parentId: string | null;
  userId: string;
  authorHandle: string | null;
  authorNickname: string;
  authorAvatar: string | null;
  content: string;
  createdAt: string;
  chtholly: boolean;
  replies: CommentItem[];
};

export type CommentListResponse = {
  items: CommentItem[];
  total: number;
  page: number;
  size: number;
  hasMore: boolean;
};

export type UserCommentActivityItem = {
  id: string;
  postId: string;
  postSlug: string;
  postTitle: string;
  parentId: string | null;
  content: string;
  createdAt: string;
};

export type UserCommentActivityPage = {
  items: UserCommentActivityItem[];
  total: number;
  page: number;
  size: number;
  hasMore: boolean;
};

export type CreateCommentRequest = {
  content: string;
  parentId?: string;
};
