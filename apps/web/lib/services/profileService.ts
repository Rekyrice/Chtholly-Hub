import { apiFetch } from "./apiClient";
import type {
  ProfileForm,
  ProfileGender,
  ProfileResponse,
  ProfileUpdatePayload,
} from "@/lib/types/profile";

const PROFILE_PREFIX = "/api/v1/profile";

function parseTags(tagJson?: string | null): string[] {
  if (!tagJson) return [];
  try {
    const parsed = JSON.parse(tagJson);
    return Array.isArray(parsed)
      ? parsed.map((tag) => String(tag).trim()).filter(Boolean)
      : [];
  } catch {
    return tagJson
      .split(/[,，]/)
      .map((tag) => tag.trim())
      .filter(Boolean);
  }
}

function toProfileGender(gender?: string | null): ProfileGender {
  if (gender === "MALE" || gender === "FEMALE") return gender;
  return "SECRET";
}

function toApiGender(gender: ProfileGender): ProfileUpdatePayload["gender"] {
  return gender === "SECRET" ? "UNKNOWN" : gender;
}

export function profileResponseToForm(profile: ProfileResponse): ProfileForm {
  return {
    nickname: profile.nickname ?? "",
    bio: profile.bio ?? "",
    birthday: profile.birthday ?? "",
    school: profile.school ?? "",
    gender: toProfileGender(profile.gender),
    tags: parseTags(profile.tagJson),
    avatarUrl: profile.avatar ?? "",
  };
}

export function profileFormToPayload(form: ProfileForm): ProfileUpdatePayload {
  return {
    nickname: form.nickname.trim(),
    bio: form.bio?.trim() ?? "",
    birthday: form.birthday || undefined,
    school: form.school?.trim() ?? "",
    gender: toApiGender(form.gender),
    tagJson: JSON.stringify(form.tags.map((tag) => tag.trim()).filter(Boolean).slice(0, 20)),
  };
}

export const profileService = {
  getProfile: () => apiFetch<ProfileResponse>(PROFILE_PREFIX),

  updateProfile: (form: ProfileForm) =>
    apiFetch<ProfileResponse>(PROFILE_PREFIX, {
      method: "PATCH",
      body: profileFormToPayload(form),
    }),

  uploadAvatar: (file: File) => {
    const formData = new FormData();
    formData.append("file", file);
    return apiFetch<ProfileResponse>(`${PROFILE_PREFIX}/avatar`, {
      method: "POST",
      body: formData,
    });
  },
};
