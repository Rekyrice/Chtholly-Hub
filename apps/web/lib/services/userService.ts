import { apiFetch } from "./apiClient";
import type { PublicUser } from "@/lib/types/user";

const USER_PREFIX = "/api/v1/users";

export const userService = {
  getByHandle: (handle: string) =>
    apiFetch<PublicUser>(`${USER_PREFIX}/${encodeURIComponent(handle)}`),
};
