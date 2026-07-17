import { afterEach, describe, expect, it, vi } from "vitest";
import {
  PostQaStreamProtocolError,
  postAiService,
  type PostQaStreamEvent,
} from "@/lib/services/postAiService";

function streamResponse(payload: string, splitAt: number[]): Response {
  const bytes = new TextEncoder().encode(payload);
  const boundaries = [...splitAt, bytes.length]
    .filter((value, index, values) => value > 0 && value <= bytes.length && value > (values[index - 1] ?? 0));
  let start = 0;
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const end of boundaries) {
        controller.enqueue(bytes.slice(start, end));
        start = end;
      }
      controller.close();
    },
  });
  return new Response(body, {
    status: 200,
    headers: { "Content-Type": "text/event-stream;charset=UTF-8" },
  });
}

async function collect(stream: AsyncGenerator<PostQaStreamEvent>) {
  const events: PostQaStreamEvent[] = [];
  for await (const event of stream) {
    events.push(event);
  }
  return events;
}

describe("postAiService.qaStream", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("parses named SSE events across arbitrary byte boundaries and posts history", async () => {
    const fetchMock = vi.fn().mockResolvedValue(streamResponse(
      "event: delta\r\ndata: 第一段\r\n\r\nevent: delta\r\ndata: 第二段\r\n\r\nevent: done\r\ndata: [DONE]\r\n\r\n",
      [1, 8, 23, 31, 47],
    ));
    vi.stubGlobal("fetch", fetchMock);

    const events = await collect(postAiService.qaStream(
      "42",
      "继续说说？",
      [{ question: "上一问", answer: "上一答" }],
    ));

    expect(events).toEqual([
      { type: "delta", data: "第一段" },
      { type: "delta", data: "第二段" },
      { type: "done" },
    ]);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/v1/posts/42/qa/stream",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({
          question: "继续说说？",
          history: [{ question: "上一问", answer: "上一答" }],
        }),
      }),
    );
  });

  it("rejects a stream that reaches EOF without an explicit done event", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(streamResponse(
      "event: delta\ndata: 只有一半\n\n",
      [5, 17],
    )));

    await expect(collect(postAiService.qaStream("42", "问题", [])))
      .rejects.toBeInstanceOf(PostQaStreamProtocolError);
  });
});
