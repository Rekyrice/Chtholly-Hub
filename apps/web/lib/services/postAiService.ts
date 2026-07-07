import { apiFetch } from "./apiClient";

const POST_AI_PREFIX = "/api/v1/posts";

export type DescriptionSuggestResponse = {
  description: string;
};

export const postAiService = {
  suggestDescription: (content: string) =>
    apiFetch<DescriptionSuggestResponse>(`${POST_AI_PREFIX}/description/suggest`, {
      method: "POST",
      body: { content },
    }),

  qaStream: (postId: string, question: string): EventSource => {
    const params = new URLSearchParams({ question });
    return new EventSource(`${POST_AI_PREFIX}/${encodeURIComponent(postId)}/qa/stream?${params}`);
  },

  reindex: (postId: string) =>
    apiFetch<number>(`${POST_AI_PREFIX}/${encodeURIComponent(postId)}/rag/reindex`, {
      method: "POST",
    }),
};
