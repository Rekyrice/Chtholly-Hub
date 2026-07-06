import { apiFetch } from "./apiClient";

const COUNTER_PREFIX = "/api/v1/counter";

type CounterResponse = {
  entityType: string;
  entityId: string;
  counts?: Record<string, number>;
  likes?: number;
  comments?: number;
  favs?: number;
  liked?: boolean;
  faved?: boolean;
};

export interface EntityCounter {
  likes: number;
  comments: number;
  favs: number;
  liked: boolean;
  faved: boolean;
}

export const counterService = {
  get: async (entityType: string, entityId: string): Promise<EntityCounter> => {
    const response = await apiFetch<CounterResponse>(
      `${COUNTER_PREFIX}/${encodeURIComponent(entityType)}/${encodeURIComponent(entityId)}`,
    );
    return {
      likes: response.likes ?? response.counts?.like ?? 0,
      comments: response.comments ?? response.counts?.comment ?? 0,
      favs: response.favs ?? response.counts?.fav ?? 0,
      liked: response.liked ?? false,
      faved: response.faved ?? false,
    };
  },
};
