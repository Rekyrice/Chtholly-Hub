import { apiFetch } from "./apiClient";
import type { DeadLetterMessage, DeadLetterPageResponse } from "@/lib/types/deadLetter";

const DEAD_LETTER_PREFIX = "/api/v1/admin/dead-letters";

type RawDeadLetterMessage = {
  id: string | number;
  topic?: string;
  sourceTopic?: string;
  messageKey?: string;
  payload?: string;
  messageValue?: string;
  failureReason?: string;
  exceptionClass?: string;
  exceptionMessage?: string;
  retryCount?: number;
  status?: string;
  createdAt?: string;
  lastRetryAt?: string;
};

type RawDeadLetterPageResponse = {
  items: RawDeadLetterMessage[];
  total?: number;
  page?: number;
  size?: number;
  hasMore?: boolean;
};

function normalizeMessage(message: RawDeadLetterMessage): DeadLetterMessage {
  const failureReason = message.failureReason ?? message.exceptionMessage ?? "";
  const topic = message.topic ?? message.sourceTopic ?? "-";
  return {
    id: String(message.id),
    topic,
    sourceTopic: message.sourceTopic,
    messageKey: message.messageKey,
    payload: message.payload ?? message.messageValue ?? message.messageKey ?? "",
    failureReason: message.exceptionClass
      ? `${message.exceptionClass}${failureReason ? `: ${failureReason}` : ""}`
      : failureReason,
    exceptionClass: message.exceptionClass,
    exceptionMessage: message.exceptionMessage,
    retryCount: message.retryCount ?? 0,
    status: message.status,
    createdAt: message.createdAt ?? "",
    lastRetryAt: message.lastRetryAt,
  };
}

function normalizePage(response: RawDeadLetterPageResponse, fallbackPage: number, fallbackSize: number): DeadLetterPageResponse {
  const page = response.page ?? fallbackPage;
  const size = response.size ?? fallbackSize;
  const total = response.total ?? response.items.length;
  return {
    items: response.items.map(normalizeMessage),
    total,
    page,
    size,
    hasMore: response.hasMore ?? page * size < total,
  };
}

export const deadLetterService = {
  list: async (page = 1, size = 20) => {
    const response = await apiFetch<RawDeadLetterPageResponse>(
      `${DEAD_LETTER_PREFIX}?page=${page}&size=${size}`,
    );
    return normalizePage(response, page, size);
  },

  replay: async (id: string) => {
    await apiFetch<RawDeadLetterMessage>(`${DEAD_LETTER_PREFIX}/${id}/replay`, {
      method: "POST",
    });
  },
};
