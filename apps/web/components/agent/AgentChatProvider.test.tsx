import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  AgentChatProvider,
  useAgentChatContext,
} from "@/components/agent/AgentChatProvider";

const mocks = vi.hoisted(() => ({
  abandonCurrentTurn: vi.fn(),
  switchSession: vi.fn(() => true),
  createSession: vi.fn(() => "created-session"),
  activateContextSession: vi.fn(() => "context-session"),
  deleteSession: vi.fn(() => true),
  clearBackendMemory: vi.fn(),
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
    renameSession: vi.fn(),
    deleteSession: mocks.deleteSession,
  }),
}));

vi.mock("@/components/agent/hooks/useAgentWebSocket", () => ({
  useAgentWebSocket: () => ({
    connected: true,
    busy: true,
    liveSteps: [],
    livePhase: "speaking",
    lastError: null,
    proactiveNotifications: [],
    visibleProactiveNotification: null,
    dismissProactiveNotification: vi.fn(),
    sendMessage: vi.fn(),
    clearConversation: vi.fn(),
    clearBackendMemory: mocks.clearBackendMemory,
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
  });

  it.each([
    ["switch", (context: ReturnType<typeof useAgentChatContext>) => context.switchSession("target-session"), mocks.switchSession],
    ["create", (context: ReturnType<typeof useAgentChatContext>) => context.createSession(), mocks.createSession],
    ["context", (context: ReturnType<typeof useAgentChatContext>) => context.activateContextSession({ contextKey: "post:new", contextTitle: "新上下文" }), mocks.activateContextSession],
    ["delete", (context: ReturnType<typeof useAgentChatContext>) => context.deleteSession("active-session"), mocks.deleteSession],
  ] as const)("abandons the active turn before a %s session migration", (_name, migrate, sessionAction) => {
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    act(() => migrate(result.current));

    expect(mocks.abandonCurrentTurn).toHaveBeenCalledOnce();
    expect(sessionAction).toHaveBeenCalledOnce();
    expect(mocks.abandonCurrentTurn.mock.invocationCallOrder[0]).toBeLessThan(
      sessionAction.mock.invocationCallOrder[0],
    );
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

  it("does not abandon when activating the current context or deleting an inactive session", () => {
    const { result } = renderHook(() => useAgentChatContext(), { wrapper });

    act(() => {
      result.current.activateContextSession({
        contextKey: "post:active",
        contextTitle: "当前上下文",
      });
      result.current.deleteSession("target-session");
    });

    expect(mocks.abandonCurrentTurn).not.toHaveBeenCalled();
    expect(mocks.activateContextSession).toHaveBeenCalledOnce();
    expect(mocks.deleteSession).toHaveBeenCalledOnce();
  });
});
