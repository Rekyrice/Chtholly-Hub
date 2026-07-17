import { apiFetch } from "./apiClient";

const POST_AI_PREFIX = "/api/v1/posts";

export type DescriptionSuggestResponse = {
  description: string;
};

export type PostQaHistoryTurn = {
  question: string;
  answer: string;
};

export type PostQaStreamEvent =
  | { type: "delta"; data: string }
  | { type: "done" };

export class PostQaStreamProtocolError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PostQaStreamProtocolError";
  }
}

function takeSseFrame(buffer: string): { frame: string; rest: string } | null {
  const delimiter = /\r\n\r\n|\n\n|\r\r/u.exec(buffer);
  if (!delimiter || delimiter.index === undefined) return null;
  const end = delimiter.index + delimiter[0].length;
  return {
    frame: buffer.slice(0, delimiter.index),
    rest: buffer.slice(end),
  };
}

function parseSseFrame(frame: string): PostQaStreamEvent | null {
  let eventName = "message";
  const dataLines: string[] = [];

  for (const line of frame.split(/\r\n|\r|\n/u)) {
    if (!line || line.startsWith(":")) continue;
    const separator = line.indexOf(":");
    const field = separator === -1 ? line : line.slice(0, separator);
    let value = separator === -1 ? "" : line.slice(separator + 1);
    if (value.startsWith(" ")) value = value.slice(1);

    if (field === "event") eventName = value;
    if (field === "data") dataLines.push(value);
  }

  const data = dataLines.join("\n");
  if (eventName === "done" || data === "[DONE]") return { type: "done" };
  if (eventName === "delta" || eventName === "message") {
    return data ? { type: "delta", data } : null;
  }
  return null;
}

async function* streamPostQa(
  postId: string,
  question: string,
  history: readonly PostQaHistoryTurn[],
  signal?: AbortSignal,
): AsyncGenerator<PostQaStreamEvent> {
  const response = await fetch(
    `${POST_AI_PREFIX}/${encodeURIComponent(postId)}/qa/stream`,
    {
      method: "POST",
      headers: {
        Accept: "text/event-stream",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ question, history }),
      credentials: "include",
      signal,
    },
  );

  if (!response.ok) {
    throw new Error(`文章问答请求失败（${response.status}）`);
  }
  if (!response.body) {
    throw new PostQaStreamProtocolError("文章问答响应没有可读取的内容流");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let receivedDone = false;

  try {
    while (true) {
      const { done, value } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });

      let next = takeSseFrame(buffer);
      while (next) {
        buffer = next.rest;
        const event = parseSseFrame(next.frame);
        if (event?.type === "done") {
          receivedDone = true;
          yield event;
          void reader.cancel().catch(() => undefined);
          return;
        }
        if (event) yield event;
        next = takeSseFrame(buffer);
      }

      if (done) break;
    }
  } finally {
    reader.releaseLock();
  }

  if (!receivedDone) {
    throw new PostQaStreamProtocolError("文章问答流在完成事件到达前结束");
  }
}

export const postAiService = {
  suggestDescription: (content: string) =>
    apiFetch<DescriptionSuggestResponse>(`${POST_AI_PREFIX}/description/suggest`, {
      method: "POST",
      body: { content },
    }),

  qaStream: (
    postId: string,
    question: string,
    history: readonly PostQaHistoryTurn[],
    signal?: AbortSignal,
  ) => streamPostQa(postId, question, history, signal),

  reindex: (postId: string) =>
    apiFetch<number>(`${POST_AI_PREFIX}/${encodeURIComponent(postId)}/rag/reindex`, {
      method: "POST",
    }),
};
