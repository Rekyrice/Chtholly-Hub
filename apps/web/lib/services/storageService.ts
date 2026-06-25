import { apiFetch } from "./apiClient";

export type PresignResponse = {
  objectKey: string;
  putUrl: string;
  headers: Record<string, string>;
  expiresIn: number;
};

export const storageService = {
  presign: (params: {
    scene: "post_content" | "post_image";
    postId: string;
    contentType: string;
    ext?: string;
  }) =>
    apiFetch<PresignResponse>("/api/v1/storage/presign", {
      method: "POST",
      body: params,
    }),

  /** 直传 OSS，返回 ETag */
  uploadPut: async (putUrl: string, body: Blob | string, headers: Record<string, string>) => {
    const res = await fetch(putUrl, { method: "PUT", headers, body });
    if (!res.ok) {
      throw new Error(`OSS 上传失败：${res.status}`);
    }
    return res.headers.get("ETag")?.replace(/"/g, "") ?? "";
  },
};
