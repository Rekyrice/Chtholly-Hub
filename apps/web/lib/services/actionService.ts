import { apiFetch } from "./apiClient";

const ACTION_PREFIX = "/api/v1/action";

export interface ActionRequest {
  entityType: string;
  entityId: string;
}

export interface ActionResponse {
  changed: boolean;
  liked?: boolean;
  faved?: boolean;
}

export const actionService = {
  like: (req: ActionRequest) =>
    apiFetch<ActionResponse>(`${ACTION_PREFIX}/like`, {
      method: "POST",
      body: req,
    }),

  unlike: (req: ActionRequest) =>
    apiFetch<ActionResponse>(`${ACTION_PREFIX}/unlike`, {
      method: "POST",
      body: req,
    }),

  fav: (req: ActionRequest) =>
    apiFetch<ActionResponse>(`${ACTION_PREFIX}/fav`, {
      method: "POST",
      body: req,
    }),

  unfav: (req: ActionRequest) =>
    apiFetch<ActionResponse>(`${ACTION_PREFIX}/unfav`, {
      method: "POST",
      body: req,
    }),
};
