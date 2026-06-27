import { apiFetch } from "./apiClient";
import type { TagItem } from "@/lib/types/tag";

const TAG_PREFIX = "/api/v1/tags";

export const tagService = {
  /** 按引用次数降序返回标签列表 */
  list: (limit = 50) =>
    apiFetch<TagItem[]>(`${TAG_PREFIX}?limit=${limit}`),
};
