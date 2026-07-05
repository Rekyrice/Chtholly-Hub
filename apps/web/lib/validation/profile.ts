import type { ProfileForm } from "@/lib/types/profile";

export const PROFILE_NICKNAME_PATTERN = /^[a-zA-Z0-9_\u4e00-\u9fa5]+$/;
export const PROFILE_MAX_AVATAR_SIZE = 2 * 1024 * 1024;
export const PROFILE_ALLOWED_IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"] as const;

export type ProfileValidationResult = {
  valid: boolean;
  errors: Partial<Record<keyof ProfileForm | "avatar", string>>;
};

export function validateProfileForm(form: ProfileForm): ProfileValidationResult {
  const errors: ProfileValidationResult["errors"] = {};
  const nickname = form.nickname.trim();

  if (!nickname) {
    errors.nickname = "昵称不能为空";
  } else if (nickname.length < 2 || nickname.length > 20) {
    errors.nickname = "昵称需要 2-20 个字符";
  } else if (!PROFILE_NICKNAME_PATTERN.test(nickname)) {
    errors.nickname = "昵称只能包含中文、字母、数字和下划线";
  }

  if ((form.bio ?? "").length > 200) {
    errors.bio = "签名最多 200 个字符";
  }

  return {
    valid: Object.keys(errors).length === 0,
    errors,
  };
}

export function validateAvatarFile(file: File): string | null {
  if (!PROFILE_ALLOWED_IMAGE_TYPES.includes(file.type as (typeof PROFILE_ALLOWED_IMAGE_TYPES)[number])) {
    return "仅支持 JPG、PNG、WebP 图片";
  }
  if (file.size > PROFILE_MAX_AVATAR_SIZE) {
    return "图片不能超过 2MB";
  }
  return null;
}
