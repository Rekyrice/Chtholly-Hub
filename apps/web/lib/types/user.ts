export interface PublicUser {
  id: string;
  handle: string;
  nickname: string;
  avatar: string | null;
  bio: string | null;
  publicPostCount: number;
}
