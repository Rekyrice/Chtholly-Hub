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
});
