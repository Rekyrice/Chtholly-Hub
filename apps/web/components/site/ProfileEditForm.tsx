"use client";

import { useRouter } from "next/navigation";
import { FormEvent, KeyboardEvent, useEffect, useMemo, useState } from "react";
import { Button } from "@/components/ui/Button";
import { AvatarUpload } from "@/components/site/AvatarUpload";
import { getStoredAuth, saveAuth } from "@/lib/auth/tokens";
import { useRequireAuth } from "@/lib/hooks/useRequireAuth";
import { ApiError } from "@/lib/services/apiClient";
import {
  profileResponseToForm,
  profileService,
} from "@/lib/services/profileService";
import type { ProfileForm, ProfileGender, ProfileResponse } from "@/lib/types/profile";
import { cn } from "@/lib/utils";
import { validateProfileForm } from "@/lib/validation/profile";

const PRESET_TAGS = [
  "治愈",
  "日常",
  "科幻",
  "奇幻",
  "音乐",
  "技术",
  "写作",
  "游戏",
  "电影",
  "轻小说",
];

const EMPTY_FORM: ProfileForm = {
  nickname: "",
  bio: "",
  birthday: "",
  school: "",
  gender: "SECRET",
  tags: [],
  avatarUrl: "",
};

const genderOptions: Array<{ value: ProfileGender; label: string }> = [
  { value: "MALE", label: "男" },
  { value: "FEMALE", label: "女" },
  { value: "SECRET", label: "保密" },
];

export default function ProfileEditForm() {
  const router = useRouter();
  const authorized = useRequireAuth();
  const [form, setForm] = useState<ProfileForm>(EMPTY_FORM);
  const [tagInput, setTagInput] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<
    ReturnType<typeof validateProfileForm>["errors"]
  >({});

  const bioCount = useMemo(() => form.bio?.length ?? 0, [form.bio]);

  useEffect(() => {
    if (!authorized) return;

    let active = true;
    const loadProfile = async () => {
      setLoading(true);
      setError(null);
      try {
        const profile = await profileService.getProfile();
        if (!active) return;
        setForm(profileResponseToForm(profile));
      } catch (err) {
        if (err instanceof ApiError && err.status === 401) {
          router.replace("/login");
          return;
        }
        if (active) {
          setError(err instanceof Error ? err.message : "资料加载失败");
        }
      } finally {
        if (active) setLoading(false);
      }
    };

    void loadProfile();
    return () => {
      active = false;
    };
  }, [authorized, router]);

  const updateForm = <K extends keyof ProfileForm>(key: K, value: ProfileForm[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }));
    setFieldErrors((prev) => ({ ...prev, [key]: undefined }));
    setMessage(null);
  };

  const addTag = (tag: string) => {
    const normalized = tag.trim();
    if (!normalized) return;
    setForm((prev) => {
      if (prev.tags.includes(normalized) || prev.tags.length >= 20) return prev;
      return { ...prev, tags: [...prev.tags, normalized] };
    });
    setTagInput("");
  };

  const removeTag = (tag: string) => {
    setForm((prev) => ({ ...prev, tags: prev.tags.filter((item) => item !== tag) }));
  };

  const onTagKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === "Enter" || event.key === "," || event.key === "，") {
      event.preventDefault();
      addTag(tagInput);
    }
    if (event.key === "Backspace" && !tagInput && form.tags.length > 0) {
      removeTag(form.tags[form.tags.length - 1]);
    }
  };

  const syncStoredUser = (profile: ProfileResponse) => {
    const stored = getStoredAuth();
    if (!stored?.accessToken) return;
    saveAuth(
      {
        accessToken: stored.accessToken,
        accessTokenExpiresAt: stored.accessTokenExpiresAt,
        refreshToken: stored.refreshToken,
        refreshTokenExpiresAt: stored.refreshTokenExpiresAt,
      },
      {
        id: profile.id,
        nickname: profile.nickname,
        avatar: profile.avatar,
        phone: profile.phone,
        handle: profile.handle,
        birthday: profile.birthday,
        school: profile.school,
        bio: profile.bio,
        gender: profile.gender,
        tagJson: profile.tagJson,
      },
    );
    window.dispatchEvent(new Event("chtholly-auth-change"));
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const validation = validateProfileForm(form);
    setFieldErrors(validation.errors);
    if (!validation.valid) {
      setError("还有资料需要调整");
      return;
    }

    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      const updated = await profileService.updateProfile(form);
      setForm(profileResponseToForm(updated));
      syncStoredUser(updated);
      setMessage("资料已更新");
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        router.replace("/login");
        return;
      }
      setError(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  if (!authorized || loading) {
    return <div className="settings-loading">资料读取中...</div>;
  }

  return (
    <form className="settings-grid" onSubmit={onSubmit} data-testid="profile-edit-form">
      <aside className="settings-avatar-panel">
        <AvatarUpload
          value={form.avatarUrl}
          nickname={form.nickname}
          onUploaded={(avatarUrl, profile) => {
            updateForm("avatarUrl", avatarUrl);
            syncStoredUser(profile);
            setMessage("头像已更新");
          }}
          onError={setError}
        />
        <div className="settings-profile-note">
          <span>{form.nickname || "未命名"}</span>
          <p>{form.bio || "还没有签名。"}</p>
        </div>
      </aside>

      <div className="settings-form-panel">
        <div className="settings-field settings-field--wide">
          <label className="field-label" htmlFor="nickname">
            昵称 <span className="text-error">*</span>
          </label>
          <input
            id="nickname"
            className={cn("field-input", fieldErrors.nickname && "settings-input--invalid")}
            value={form.nickname}
            maxLength={20}
            onChange={(event) => updateForm("nickname", event.target.value)}
            required
          />
          {fieldErrors.nickname && (
            <p className="settings-field-error">{fieldErrors.nickname}</p>
          )}
        </div>

        <div className="settings-field settings-field--wide">
          <label className="field-label" htmlFor="bio">
            签名
          </label>
          <textarea
            id="bio"
            className={cn("field-input settings-textarea", fieldErrors.bio && "settings-input--invalid")}
            value={form.bio}
            maxLength={200}
            onChange={(event) => updateForm("bio", event.target.value)}
          />
          <div className="settings-field-hint">
            <span>{fieldErrors.bio ?? ""}</span>
            <span>{bioCount}/200</span>
          </div>
        </div>

        <div className="settings-field">
          <label className="field-label" htmlFor="birthday">
            生日
          </label>
          <input
            id="birthday"
            type="date"
            className="field-input"
            value={form.birthday}
            onChange={(event) => updateForm("birthday", event.target.value)}
          />
        </div>

        <div className="settings-field">
          <label className="field-label" htmlFor="school">
            学校
          </label>
          <input
            id="school"
            className="field-input"
            value={form.school}
            maxLength={128}
            onChange={(event) => updateForm("school", event.target.value)}
          />
        </div>

        <fieldset className="settings-field settings-field--wide">
          <legend className="field-label">性别</legend>
          <div className="settings-segmented">
            {genderOptions.map((option) => (
              <label
                key={option.value}
                className={cn(
                  "settings-segmented__item",
                  form.gender === option.value && "settings-segmented__item--active",
                )}
              >
                <input
                  type="radio"
                  name="gender"
                  value={option.value}
                  checked={form.gender === option.value}
                  onChange={() => updateForm("gender", option.value)}
                />
                <span>{option.label}</span>
              </label>
            ))}
          </div>
        </fieldset>

        <div className="settings-field settings-field--wide">
          <label className="field-label" htmlFor="tags">
            兴趣标签
          </label>
          <div className="settings-tags-box">
            <div className="settings-tags">
              {form.tags.map((tag) => (
                <button
                  type="button"
                  key={tag}
                  className="settings-tag settings-tag--selected"
                  onClick={() => removeTag(tag)}
                >
                  {tag}
                </button>
              ))}
              <input
                id="tags"
                value={tagInput}
                onChange={(event) => setTagInput(event.target.value)}
                onKeyDown={onTagKeyDown}
                className="settings-tag-input"
                placeholder={form.tags.length ? "" : "输入标签"}
              />
            </div>
            <div className="settings-preset-tags" aria-label="预设兴趣标签">
              {PRESET_TAGS.map((tag) => (
                <button
                  type="button"
                  key={tag}
                  className={cn(
                    "settings-tag",
                    form.tags.includes(tag) && "settings-tag--disabled",
                  )}
                  onClick={() => addTag(tag)}
                  disabled={form.tags.includes(tag)}
                >
                  {tag}
                </button>
              ))}
            </div>
          </div>
        </div>

        {(error || message) && (
          <p
            className={cn("settings-message", error ? "settings-message--error" : "settings-message--ok")}
            aria-live="polite"
          >
            {error ?? message}
          </p>
        )}

        <div className="settings-actions">
          <Button type="submit" size="lg" loading={saving}>
            保存
          </Button>
        </div>
      </div>
    </form>
  );
}
