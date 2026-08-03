import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { useAgentSessions } from "@/components/agent/hooks/useAgentSessions";

const SESSIONS_KEY = "chtholly-agent-sessions";
const ACTIVE_KEY = "chtholly-agent-active-session";

function storedSessions() {
  return JSON.parse(localStorage.getItem(SESSIONS_KEY) ?? "[]") as Array<{
    id: string;
    messages: Array<{
      role: string;
      content: string;
      streaming?: boolean;
      completionState?: string;
    }>;
  }>;
}

function seedSessions() {
  localStorage.setItem(ACTIVE_KEY, "source-session");
  localStorage.setItem(SESSIONS_KEY, JSON.stringify([
    {
      id: "source-session",
      title: "来源会话",
      messages: [],
      createdAt: 1,
      updatedAt: 2,
    },
    {
      id: "target-session",
      title: "目标会话",
      messages: [{ id: "target-user", role: "user", content: "目标消息" }],
      createdAt: 1,
      updatedAt: 1,
    },
  ]));
}

const partialMessages = [
  { id: "source-user", role: "user" as const, content: "来源问题" },
  {
    id: "source-assistant",
    role: "assistant" as const,
    content: "- **尚未完成的回答",
    streaming: true,
  },
];

describe("useAgentSessions article context", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("creates an isolated article session and reuses it by context key", async () => {
    const { result } = renderHook(() => useAgentSessions());
    await waitFor(() => expect(result.current.hydrated).toBe(true));
    const ordinarySessionId = result.current.activeSessionId;

    let articleSessionId = "";
    act(() => {
      articleSessionId = result.current.activateContextSession({
        contextKey: "post:dungeon-meshi",
        contextTitle: "吃掉红龙这件事",
        postId: "42",
      });
    });

    expect(articleSessionId).not.toBe(ordinarySessionId);
    expect(result.current.activeSessionContextRef.current).toEqual({
      contextKey: "post:dungeon-meshi",
      contextTitle: "吃掉红龙这件事",
      postId: "42",
    });
    expect(result.current.messages).toEqual([]);

    let reusedId = "";
    act(() => {
      result.current.createSession();
      reusedId = result.current.activateContextSession({
        contextKey: "post:dungeon-meshi",
        contextTitle: "吃掉红龙这件事",
        postId: "42",
      });
    });

    expect(reusedId).toBe(articleSessionId);
    expect(result.current.activeSessionId).toBe(articleSessionId);
  });

  it("normalizes restored streaming replies as interrupted in memory and localStorage", async () => {
    localStorage.setItem(ACTIVE_KEY, "restored-session");
    localStorage.setItem(SESSIONS_KEY, JSON.stringify([
      {
        id: "restored-session",
        title: "恢复会话",
        messages: [
          { id: "partial", role: "assistant", content: "**未完成", streaming: true },
          { id: "legacy", role: "assistant", content: "旧版完成回答" },
        ],
        createdAt: 1,
        updatedAt: 2,
      },
    ]));

    const { result } = renderHook(() => useAgentSessions());
    await waitFor(() => expect(result.current.hydrated).toBe(true));

    expect(result.current.messages).toEqual([
      expect.objectContaining({
        id: "partial",
        streaming: false,
        completionState: "interrupted",
      }),
      { id: "legacy", role: "assistant", content: "旧版完成回答" },
    ]);
    await waitFor(() => expect(storedSessions()[0].messages).toEqual([
      expect.objectContaining({
        id: "partial",
        streaming: false,
        completionState: "interrupted",
      }),
      { id: "legacy", role: "assistant", content: "旧版完成回答" },
    ]));
  });

  it.each(["switch", "create", "context"] as const)(
    "persists a partial reply as interrupted before a %s session migration",
    async (migration) => {
      seedSessions();
      const { result } = renderHook(() => useAgentSessions());
      await waitFor(() => expect(result.current.hydrated).toBe(true));

      act(() => result.current.setMessages(partialMessages));
      expect(result.current.messages.at(-1)).toMatchObject({ streaming: true });

      act(() => {
        if (migration === "switch") result.current.switchSession("target-session");
        if (migration === "create") result.current.createSession();
        if (migration === "context") {
          result.current.activateContextSession({
            contextKey: "post:new-context",
            contextTitle: "新上下文",
          });
        }
      });

      await waitFor(() => {
        const source = storedSessions().find((session) => session.id === "source-session");
        expect(source?.messages.at(-1)).toMatchObject({
          role: "assistant",
          content: "- **尚未完成的回答",
          streaming: false,
          completionState: "interrupted",
        });
      });
      expect(result.current.messages.some((message) => message.id === "source-assistant")).toBe(false);
    },
  );

  it("moves to a clean fallback when deleting an active session with a partial reply", async () => {
    seedSessions();
    const { result } = renderHook(() => useAgentSessions());
    await waitFor(() => expect(result.current.hydrated).toBe(true));
    act(() => result.current.setMessages(partialMessages));

    act(() => result.current.deleteSession("source-session"));

    expect(result.current.activeSessionId).toBe("target-session");
    expect(result.current.messages).toEqual([
      { id: "target-user", role: "user", content: "目标消息" },
    ]);
    expect(storedSessions().some((session) => session.id === "source-session")).toBe(false);
  });
});
