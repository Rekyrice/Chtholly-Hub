import { apiFetch } from "./apiClient";

export type PresignResponse = {
  objectKey: string;
  putUrl: string;
  headers: Record<string, string>;
  expiresIn: number;
  /** PUT：OSS 预签名；POST：本地 multipart 上传 */
  method: string;
  /** 上传完成后可公开访问的 URL（用于 Markdown 插图） */
  publicUrl?: string;
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

  /** 直传存储，返回 ETag */
  uploadPut: async (
    presign: PresignResponse,
    body: Blob | string,
  ): Promise<string> => {
    const method = (presign.method ?? "PUT").toUpperCase();
    if (method === "POST") {
      const form = new FormData();
      form.append("objectKey", presign.objectKey);
      const contentType =
        presign.headers["Content-Type"] ?? "application/octet-stream";
      const blob =
        typeof body === "string"
          ? new Blob([body], { type: contentType })
          : body;
      form.append("file", blob, "upload");
      const res = await apiFetch<{ etag: string }>("/api/v1/storage/upload", {
        method: "POST",
        body: form,
      });
      return res.etag ?? "";
    }

    const res = await fetch(presign.putUrl, {
      method: "PUT",
      headers: presign.headers,
      body,
    });
    if (!res.ok) {
      throw new Error(`OSS 上传失败：${res.status}`);
    }
    return res.headers.get("ETag")?.replace(/"/g, "") ?? "";
  },
};
