import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useAgentWebSocket } from "@/components/agent/hooks/useAgentWebSocket";

vi.mock("@/lib/agent/wsUrl", () => ({
  getAgentWsUrl: vi.fn(async () => "ws://example.test/api/v1/agent/ws"),
}));

let sockets: MockWebSocket[] = [];

class MockWebSocket {
  static readonly OPEN = 1;

  readonly readyState = MockWebSocket.OPEN;
  onopen: ((event: Event) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  readonly close = vi.fn();
  readonly send = vi.fn();

  constructor(readonly url: string) {
    sockets.push(this);
  }

  receive(envelope: Record<string, unknown>) {
    this.onmessage?.({
      data: JSON.stringify(envelope),
    } as MessageEvent);
  }
}

describe("useAgentWebSocket proactive notifications", () => {
  beforeEach(() => {
    sockets = [];
    vi.stubGlobal("WebSocket", MockWebSocket);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("assigns a distinct instance id to every identical proactive envelope", async () => {
    const setMessages = vi.fn();
    const { result } = renderHook(() =>
      useAgentWebSocket({
        loggedIn: true,
        hydrated: true,
        activeSessionIdRef: { current: "session-1" },
        messages: [],
        setMessages,
        onInputConsumed: vi.fn(),
      }),
    );
    await waitFor(() => expect(sockets).toHaveLength(1));
    const envelope = {
      type: "proactive",
      data: {
        type: "thought",
        message: "完全相同的主动通知",
        timestamp: "2026-07-27T08:00:00.000Z",
        channel: "FLOATING",
      },
    };

    act(() => sockets[0].receive(envelope));
    const first = result.current.visibleProactiveNotification;
    act(() => sockets[0].receive(envelope));
    const second = result.current.visibleProactiveNotification;

    expect(first?.instanceId).toEqual(expect.any(String));
    expect(second?.instanceId).toEqual(expect.any(String));
    expect(second?.instanceId).not.toBe(first?.instanceId);
    expect(result.current.proactiveNotifications).toHaveLength(2);
  });
});
