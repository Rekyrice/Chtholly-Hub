"use client";

import { Camera, UploadCloud } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { extractErrorMessage } from "@/lib/hooks/useErrorMessage";
import { profileService } from "@/lib/services/profileService";
import type { ProfileResponse } from "@/lib/types/profile";
import { cn } from "@/lib/utils";
import { validateAvatarFile } from "@/lib/validation/profile";

type AvatarUploadProps = {
  value?: string;
  nickname?: string;
  onUploaded: (avatarUrl: string, profile: ProfileResponse) => void;
  onError?: (message: string) => void;
};

export function AvatarUpload({ value, nickname, onUploaded, onError }: AvatarUploadProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);

  useEffect(() => {
    return () => {
      if (preview) URL.revokeObjectURL(preview);
    };
  }, [preview]);

  const displaySrc = preview ?? value ?? "";
  const initial = (nickname?.trim()?.[0] ?? "C").toUpperCase();

  const handleFile = async (file?: File) => {
    if (!file) return;
    const error = validateAvatarFile(file);
    if (error) {
      onError?.(error);
      return;
    }

    const objectUrl = URL.createObjectURL(file);
    setPreview((prev) => {
      if (prev) URL.revokeObjectURL(prev);
      return objectUrl;
    });
    setUploading(true);
    try {
      const updated = await profileService.uploadAvatar(file);
      onUploaded(updated.avatar ?? objectUrl, updated);
      if (updated.avatar) {
        setPreview((prev) => {
          if (prev) URL.revokeObjectURL(prev);
          return null;
        });
      }
    } catch (err) {
      onError?.(extractErrorMessage(err, "头像上传失败"));
    } finally {
      setUploading(false);
    }
  };

  return (
    <div
      className={cn("avatar-upload", dragging && "avatar-upload--dragging")}
      onDragOver={(event) => {
        event.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(event) => {
        event.preventDefault();
        setDragging(false);
        void handleFile(event.dataTransfer.files?.[0]);
      }}
    >
      <button
        type="button"
        className="avatar-upload__preview"
        onClick={() => inputRef.current?.click()}
        disabled={uploading}
        aria-label="上传头像"
      >
        {displaySrc ? (
          <img src={displaySrc} alt="头像预览" className="avatar-upload__image" />
        ) : (
          <span className="avatar-upload__initial">{initial}</span>
        )}
        <span className="avatar-upload__camera" aria-hidden="true">
          <Camera size={18} />
        </span>
      </button>

      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        className="sr-only"
        onChange={(event) => void handleFile(event.target.files?.[0])}
      />

      <div className="avatar-upload__meta">
        <div className="avatar-upload__title">
          <UploadCloud size={16} aria-hidden="true" />
          <span>{uploading ? "上传中..." : "头像"}</span>
        </div>
        <p>JPG / PNG / WebP，最大 2MB</p>
      </div>
    </div>
  );
}
