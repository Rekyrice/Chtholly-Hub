import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { useAgentSessions } from "@/components/agent/hooks/useAgentSessions";

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
});
