import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  AgentChatProvider,
  useAgentChatContext,
} from "@/components/agent/AgentChatProvider";

const mocks = vi.hoisted(() => ({
  connected: true,
  busy: true,
  turnActive: true,
  hasActiveTurn: vi.fn(() => true),
  abandonCurrentTurn: vi.fn(),
  sendMessage: vi.fn(async () => undefined),
  switchSession: vi.fn(() => true),
  createSession: vi.fn(() => "created-session"),
  activateContextSession: vi.fn(() => "context-session"),
  renameSession: vi.fn(),
  deleteSession: vi.fn(() => true),
  clearLocalConversation: vi.fn(),
  clearSessionMemory: vi.fn(async () => undefined),
}));

vi.mock("@/lib/services/agentService", () => ({
  agentService: {
    clearSessionMemory: mocks.clearSessionMemory,
  },
}));

vi.mock("@/lib/auth/tokens", () => ({
  isLoggedIn: vi.fn(() => true),
  purgeExpiredAuth: vi.fn(),
}));

vi.mock("@/components/agent/hooks/useAgentPreferences", () => ({
  useAgentPreferences: () => ({
    showSteps: false,
    setShowSteps: vi.fn(),
    workspaceDark: false,
    setWorkspaceDark: vi.fn(),
    richMarkdown: true,
    setRichMarkdown: vi.fn(),
  }),
}));

vi.mock("@/components/agent/hooks/useAgentSessions", () => ({
  useAgentSessions: () => ({
    activeSessionId: "active-session",
    activeSessionIdRef: { current: "active-session" },
    activeSessionContextRef: { current: null },
    sessions: [
      { id: "active-session", contextKey: "post:active", messages: [] },
      { id: "target-session", contextKey: "post:target", messages: [] },
    ],
    messages: [],
    setMessages: vi.fn(),
    hydrated: true,
    switchSession: mocks.switchSession,
    createSession: mocks.createSession,
    activateContextSession: mocks.activateContextSession,
    renameSession: mocks.renameSession,
    deleteSession: mocks.deleteSession,
  }),
}));

vi.mock("@/components/agent/hooks/useAgentWebSocket", () => ({
  useAgentWebSocket: () => ({
    connected: mocks.connected,
    busy: mocks.busy,
    turnActive: mocks.turnActive,
    hasActiveTurn: mocks.hasActiveTurn,
    liveSteps: [],
    livePhase: "speaking",
    lastError: null,
    proactiveNotifications: [],
    visibleProactiveNotification: null,
    dismissProactiveNotification: vi.fn(),
    sendMessage: mocks.sendMessage,
    clearConversation: vi.fn(),
    clearLocalConversation: mocks.clearLocalConversation,
    abandonCurrentTurn: mocks.abandonCurrentTurn,
    resetTransient: vi.fn(),
    streaming: true,
  }),
}));

function wrapper({ children }: { children: ReactNode }) {
  return <AgentChatProvider>{children}</AgentChatProvider>;
}

describe("AgentChatProvider session migration", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.connected = true;
    mocks.busy = true;
    mocks.turnActive = true;
    mocks.hasActiveTurn.mockReturnValue(true);
    mocks.sendMessage.mockReset().mockResolvedValue(undefined);
    mocks.clearSessionMemory.mockReset().mockResolvedValue(undefined);
  });

  it.each([
    ["switch", (context: ReturnType<typeof useAgentChatContext>) => context.switchSession("target-session"), mocks.switchSession],
    ["create", (context: ReturnType<typeof useAgentChatContext>) => context.createSession(), mocks.createSession],
    ["context", (context: ReturnType<typeof useAgentChatContext>) => context.activateContextSession({ contextKey: "post:new", contextTitle: "新上下文" }), mocks.activateContextSession],
  ] as const)("abandons the active turn before a %s session migration", (_name, migrate, sessionAction) => {
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    act(() => migrate(result.current));

    expect(mocks.abandonCurrentTurn).toHaveBeenCalledOnce();
    expect(sessionAction).toHaveBeenCalledOnce();
    expect(mocks.abandonCurrentTurn.mock.invocationCallOrder[0]).toBeLessThan(
      sessionAction.mock.invocationCallOrder[0],
    );
  });

  it("refuses to delete the active session while a ref-backed turn is active", async () => {
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    await act(async () => result.current.deleteSession("active-session"));

    expect(mocks.clearSessionMemory).not.toHaveBeenCalled();
    expect(mocks.abandonCurrentTurn).not.toHaveBeenCalled();
    expect(mocks.deleteSession).not.toHaveBeenCalled();
  });

  it("awaits HTTP memory clearing before deleting an idle active session", async () => {
    let resolveClear!: () => void;
    mocks.clearSessionMemory.mockImplementationOnce(() => new Promise<void>((resolve) => {
      resolveClear = resolve;
    }));
    mocks.busy = false;
    mocks.connected = false;
    mocks.turnActive = false;
    mocks.hasActiveTurn.mockReturnValue(false);
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    let deletion!: Promise<void>;
    act(() => {
      deletion = result.current.deleteSession("active-session");
    });

    expect(mocks.clearSessionMemory).toHaveBeenCalledWith("active-session");
    expect(mocks.deleteSession).not.toHaveBeenCalled();
    expect(result.current.deletingSessionIds).toContain("active-session");

    await act(async () => {
      resolveClear();
      await deletion;
    });

    expect(mocks.abandonCurrentTurn).toHaveBeenCalledOnce();
    expect(mocks.deleteSession).toHaveBeenCalledWith("active-session");
    expect(result.current.deletingSessionIds).not.toContain("active-session");
  });

  it("keeps the local session and exposes retryable feedback when HTTP clearing fails", async () => {
    mocks.busy = false;
    mocks.turnActive = false;
    mocks.hasActiveTurn.mockReturnValue(false);
    mocks.clearSessionMemory.mockRejectedValueOnce(new Error("offline"));
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    await act(async () => result.current.deleteSession("active-session"));

    expect(mocks.deleteSession).not.toHaveBeenCalled();
    expect(mocks.abandonCurrentTurn).not.toHaveBeenCalled();
    expect(result.current.sessionDeleteError).toMatch(/未能删除会话/);
    expect(result.current.deletingSessionIds).toEqual([]);

    await act(async () => result.current.sendMessage("重新开始"));
    expect(result.current.sessionDeleteError).toBeNull();

    await act(async () => result.current.deleteSession("active-session"));

    expect(mocks.clearSessionMemory).toHaveBeenCalledTimes(2);
    expect(mocks.deleteSession).toHaveBeenCalledWith("active-session");
    expect(result.current.sessionDeleteError).toBeNull();
  });

  it("deduplicates repeated deletes while the HTTP request is pending", async () => {
    let resolveClear!: () => void;
    mocks.busy = false;
    mocks.turnActive = false;
    mocks.hasActiveTurn.mockReturnValue(false);
    mocks.clearSessionMemory.mockImplementationOnce(() => new Promise<void>((resolve) => {
      resolveClear = resolve;
    }));
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    let first!: Promise<void>;
    act(() => {
      first = result.current.deleteSession("target-session");
      void result.current.deleteSession("target-session");
    });

    expect(mocks.clearSessionMemory).toHaveBeenCalledOnce();
    expect(mocks.deleteSession).not.toHaveBeenCalled();

    act(() => {
      result.current.switchSession("target-session");
      result.current.renameSession("target-session", "不应写入");
    });
    expect(mocks.switchSession).not.toHaveBeenCalled();
    expect(mocks.renameSession).not.toHaveBeenCalled();

    await act(async () => {
      resolveClear();
      await first;
    });
    expect(mocks.deleteSession).toHaveBeenCalledOnce();
  });

  it("blocks same-batch deletion when send marks the active turn synchronously", async () => {
    mocks.busy = false;
    mocks.turnActive = false;
    mocks.hasActiveTurn.mockReturnValue(false);
    mocks.sendMessage.mockImplementationOnce(async () => {
      mocks.hasActiveTurn.mockReturnValue(true);
    });
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    await act(async () => {
      const send = result.current.sendMessage("同批发送");
      const deletion = result.current.deleteSession("active-session");
      await Promise.all([send, deletion]);
    });

    expect(mocks.sendMessage).toHaveBeenCalledOnce();
    expect(mocks.clearSessionMemory).not.toHaveBeenCalled();
    expect(mocks.deleteSession).not.toHaveBeenCalled();
  });

  it("blocks a same-batch send after active-session deletion starts", async () => {
    let resolveClear!: () => void;
    mocks.busy = false;
    mocks.turnActive = false;
    mocks.hasActiveTurn.mockReturnValue(false);
    mocks.clearSessionMemory.mockImplementationOnce(() => new Promise<void>((resolve) => {
      resolveClear = resolve;
    }));
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    let deletion!: Promise<void>;
    await act(async () => {
      deletion = result.current.deleteSession("active-session");
      await result.current.sendMessage("删除中的发送");
    });

    expect(mocks.sendMessage).not.toHaveBeenCalled();
    expect(mocks.deleteSession).not.toHaveBeenCalled();

    await act(async () => {
      resolveClear();
      await deletion;
    });
    expect(mocks.deleteSession).toHaveBeenCalledWith("active-session");
  });

  it("awaits HTTP memory clearing before clearing an offline local conversation", async () => {
    let resolveClear!: () => void;
    mocks.connected = false;
    mocks.busy = false;
    mocks.turnActive = false;
    mocks.hasActiveTurn.mockReturnValue(false);
    mocks.clearSessionMemory.mockImplementationOnce(() => new Promise<void>((resolve) => {
      resolveClear = resolve;
    }));
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    let clearing!: Promise<boolean>;
    act(() => {
      clearing = result.current.clearConversation();
    });

    expect(mocks.clearSessionMemory).toHaveBeenCalledWith("active-session");
    expect(mocks.clearLocalConversation).not.toHaveBeenCalled();
    expect(result.current.clearingConversation).toBe(true);

    await act(async () => {
      resolveClear();
      await expect(clearing).resolves.toBe(true);
    });

    expect(mocks.clearLocalConversation).toHaveBeenCalledOnce();
    expect(result.current.clearingConversation).toBe(false);
    expect(result.current.conversationClearError).toBeNull();
  });

  it("refuses to clear while a ticket, handshake, or accepted turn is active", async () => {
    mocks.busy = false;
    mocks.turnActive = true;
    mocks.hasActiveTurn.mockReturnValue(true);
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    await act(async () => {
      await expect(result.current.clearConversation()).resolves.toBe(false);
    });

    expect(mocks.clearSessionMemory).not.toHaveBeenCalled();
    expect(mocks.clearLocalConversation).not.toHaveBeenCalled();
  });

  it("preserves the local conversation and exposes retry feedback when HTTP clearing fails", async () => {
    mocks.connected = false;
    mocks.busy = false;
    mocks.turnActive = false;
    mocks.hasActiveTurn.mockReturnValue(false);
    mocks.clearSessionMemory.mockRejectedValueOnce(new Error("offline"));
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    await act(async () => {
      await expect(result.current.clearConversation()).resolves.toBe(false);
    });

    expect(mocks.clearLocalConversation).not.toHaveBeenCalled();
    expect(result.current.conversationClearError).toMatch(/未能清空当前对话/);
    expect(result.current.clearingConversation).toBe(false);

    act(() => result.current.switchSession("target-session"));
    expect(result.current.conversationClearError).toBeNull();
  });

  it("does not abandon for the current or an unknown session", () => {
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    act(() => {
      result.current.switchSession("active-session");
      result.current.switchSession("missing-session");
    });

    expect(mocks.abandonCurrentTurn).not.toHaveBeenCalled();
    expect(mocks.switchSession).not.toHaveBeenCalled();
  });

  it("clears an inactive session over HTTP without abandoning the active turn", async () => {
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    await act(async () => {
      result.current.activateContextSession({
        contextKey: "post:active",
        contextTitle: "当前上下文",
      });
      await result.current.deleteSession("target-session");
    });

    expect(mocks.abandonCurrentTurn).not.toHaveBeenCalled();
    expect(mocks.activateContextSession).toHaveBeenCalledOnce();
    expect(mocks.clearSessionMemory).toHaveBeenCalledWith("target-session");
    expect(mocks.deleteSession).toHaveBeenCalledOnce();
  });
});
