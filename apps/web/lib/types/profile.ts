export type ProfileGender = "MALE" | "FEMALE" | "SECRET";

export type ProfileForm = {
  nickname: string;
  bio?: string;
  birthday?: string;
  school?: string;
  gender: ProfileGender;
  tags: string[];
  avatarUrl?: string;
};

export type ProfileResponse = {
  id: number;
  nickname: string;
  avatar?: string | null;
  bio?: string | null;
  handle?: string | null;
  gender?: string | null;
  birthday?: string | null;
  school?: string | null;
  phone?: string | null;
  email?: string | null;
  tagJson?: string | null;
};

export type ProfileUpdatePayload = {
  nickname?: string;
  bio?: string;
  birthday?: string;
  school?: string;
  gender?: "MALE" | "FEMALE" | "UNKNOWN";
  tagJson?: string;
};
