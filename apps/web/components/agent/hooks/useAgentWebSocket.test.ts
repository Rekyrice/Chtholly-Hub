import { describe, expect, it } from "vitest";
import { buildAgentChatPayload } from "@/components/agent/hooks/useAgentWebSocket";

describe("buildAgentChatPayload", () => {
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
      sessionId: "session-2",
      message: "今天过得怎么样？",
      context: {
        page: "/chtholly",
        title: "珂朵莉的房间",
      },
    });
    expect(payload).not.toHaveProperty("taskType");
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
