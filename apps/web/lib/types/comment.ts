export type CommentItem = {
  id: string;
  postId: string;
  parentId: string | null;
  userId: string;
  authorNickname: string;
  authorAvatar: string | null;
  content: string;
  createdAt: string;
  replies: CommentItem[];
};

export type CommentListResponse = {
  items: CommentItem[];
  total: number;
  page: number;
  size: number;
  hasMore: boolean;
};

export type CreateCommentRequest = {
  content: string;
  parentId?: string;
};
