export type RelationStatus = {
  following: boolean;
  followedBy: boolean;
  mutual: boolean;
};

export type UserCounter = {
  followings: number;
  followers: number;
  posts: number;
  likedPosts: number;
  favedPosts: number;
};

export type ProfileResponse = {
  id: number | string;
  handle: string;
  nickname: string;
  avatar?: string | null;
  bio?: string | null;
};

export type PageResponse<T> = {
  items: T[];
  nextCursor?: string | null;
  hasMore: boolean;
};

export type UserId = number | string;
