import { afterEach, describe, expect, it, vi } from "vitest";
import { buildAgentChatPayload } from "@/components/agent/hooks/useAgentWebSocket";

describe("buildAgentChatPayload", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("includes explicit taskType and structured article context for Skill requests", () => {
    expect(
      buildAgentChatPayload({
        sessionId: "session-1",
        message: "解释时间的重量",
        pathname: "/agent",
        title: "珂朵莉",
        search: "?taskType=page-explain&context=post%3Afrieren-review",
        taskType: "page-explain",
      }),
    ).toEqual({
      type: "chat",
      requestId: expect.any(String),
      sessionId: "session-1",
      message: "解释时间的重量",
      taskType: "page-explain",
      context: {
        page: "/agent",
        title: "珂朵莉",
        source: "post:frieren-review",
        postSlug: "frieren-review",
      },
    });
  });

  it("keeps ordinary chat backward compatible without a taskType field", () => {
    const payload = buildAgentChatPayload({
      sessionId: "session-2",
      message: "今天过得怎么样？",
      pathname: "/chtholly",
      title: "珂朵莉的房间",
      search: "",
    });

    expect(payload).toEqual({
      type: "chat",
      requestId: expect.any(String),
      sessionId: "session-2",
      message: "今天过得怎么样？",
      context: {
        page: "/chtholly",
        title: "珂朵莉的房间",
      },
    });
    expect(payload).not.toHaveProperty("taskType");
  });

  it("uses a fresh crypto.randomUUID value for every payload", () => {
    const randomUUID = vi.spyOn(globalThis.crypto, "randomUUID")
      .mockReturnValueOnce("11111111-1111-4111-8111-111111111111")
      .mockReturnValueOnce("22222222-2222-4222-8222-222222222222");
    const input = {
      sessionId: "session-uuid",
      message: "继续",
      pathname: "/agent",
      title: "Agent",
      search: "",
    };

    const first = buildAgentChatPayload(input);
    const second = buildAgentChatPayload(input);

    expect(first.requestId).toBe("11111111-1111-4111-8111-111111111111");
    expect(second.requestId).toBe("22222222-2222-4222-8222-222222222222");
    expect(randomUUID).toHaveBeenCalledTimes(2);
  });

  it("falls back to a compatible UUID when crypto.randomUUID is unavailable", () => {
    const getRandomValues = vi.fn((bytes: Uint8Array) => bytes.fill(0));
    vi.stubGlobal("crypto", { getRandomValues });

    try {
      const payload = buildAgentChatPayload({
        sessionId: "session-fallback",
        message: "兼容旧浏览器",
        pathname: "/agent",
        title: "Agent",
        search: "",
      });

      expect(payload.requestId).toBe("00000000-0000-4000-8000-000000000000");
      expect(getRandomValues).toHaveBeenCalledOnce();
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it("uses the active session context and ignores stale context query parameters", () => {
    expect(
      buildAgentChatPayload({
        sessionId: "session-3",
        message: "总结三个观点",
        pathname: "/agent",
        title: "Agent",
        search: "?context=post%3Aold-post",
        sessionContext: {
          contextKey: "post:dungeon-meshi",
          contextTitle: "吃掉红龙这件事",
          postId: "42",
        },
      }),
    ).toMatchObject({
      context: {
        source: "post:dungeon-meshi",
        postSlug: "dungeon-meshi",
        postId: "42",
        title: "吃掉红龙这件事",
      },
    });

    expect(
      buildAgentChatPayload({
        sessionId: "session-4",
        message: "普通聊天",
        pathname: "/agent",
        title: "Agent",
        search: "?context=post%3Aold-post",
        sessionContext: null,
      }).context,
    ).not.toHaveProperty("source");
  });
});
