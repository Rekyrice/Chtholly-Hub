import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useAgentWebSocket } from "@/components/agent/hooks/useAgentWebSocket";
import { getAgentWsUrl } from "@/lib/agent/wsUrl";
import type { AgentWsEnvelope, ChatMessage } from "@/lib/types/agent";

vi.mock("@/lib/agent/wsUrl", () => ({
  getAgentWsUrl: vi.fn(async () => "ws://example.test/api/v1/agent/ws"),
}));

let sockets: MockWebSocket[] = [];
let socketReadyStates: number[] = [];

class MockWebSocket {
  static readonly CONNECTING = 0;
  static readonly OPEN = 1;
  static readonly CLOSED = 3;

  readyState: number;
  onopen: ((event: Event) => void) | null = null;
  onclose: ((event: CloseEvent) => void) | null = null;
  onerror: ((event: Event) => void) | null = null;
  onmessage: ((event: MessageEvent) => void) | null = null;
  readonly close = vi.fn();
  readonly send = vi.fn();

  constructor(readonly url: string) {
    this.readyState = socketReadyStates.shift() ?? MockWebSocket.OPEN;
    sockets.push(this);
  }

  receive(envelope: Record<string, unknown>) {
    this.onmessage?.({
      data: JSON.stringify(envelope),
    } as MessageEvent);
  }

  disconnect() {
    this.readyState = MockWebSocket.CLOSED;
    this.onclose?.({} as CloseEvent);
  }

  fail() {
    this.onerror?.({} as Event);
  }
}

function renderAgentHook(initialMessages: ChatMessage[] = []) {
  const activeSessionIdRef = { current: "session-1" };
  const activeSessionContextRef = { current: null };
  const onInputConsumed = vi.fn();
  const hook = renderHook(() => {
    const [messages, setMessages] = useState(initialMessages);
    const socketState = useAgentWebSocket({
      loggedIn: true,
      hydrated: true,
      activeSessionIdRef,
      activeSessionContextRef,
      messages,
      setMessages,
      onInputConsumed,
    });
    return { ...socketState, messages };
  });
  return { ...hook, onInputConsumed, activeSessionIdRef };
}

function sentChatPayload(socket: MockWebSocket, index = 0) {
  return JSON.parse(String(socket.send.mock.calls[index]?.[0])) as {
    requestId: string;
  };
}

describe("useAgentWebSocket proactive notifications", () => {
  beforeEach(() => {
    sockets = [];
    socketReadyStates = [];
    vi.mocked(getAgentWsUrl).mockReset().mockResolvedValue("ws://example.test/api/v1/agent/ws");
    vi.stubGlobal("WebSocket", MockWebSocket);
  });

  afterEach(() => {
    vi.useRealTimers();
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
        activeSessionContextRef: { current: null },
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

describe("useAgentWebSocket turn protocol", () => {
  beforeEach(() => {
    sockets = [];
    socketReadyStates = [];
    vi.mocked(getAgentWsUrl).mockReset().mockResolvedValue("ws://example.test/api/v1/agent/ws");
    vi.stubGlobal("WebSocket", MockWebSocket);
  });

  afterEach(() => {
    vi.useRealTimers();
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("does not let two sends from the same render overwrite the current request", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));

    await act(async () => {
      await Promise.all([
        result.current.sendMessage("第一条"),
        result.current.sendMessage("第二条"),
      ]);
    });

    expect(sockets[0].send).toHaveBeenCalledOnce();
    expect(result.current.messages).toEqual([
      expect.objectContaining({ role: "user", content: "第一条" }),
    ]);
    expect(result.current.busy).toBe(true);
  });

  it("binds accepted only to the current request and ignores events outside that turn", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    await act(async () => result.current.sendMessage("第一轮"));
    const { requestId } = sentChatPayload(sockets[0]);

    act(() => {
      sockets[0].receive({
        type: "accepted",
        requestId: "stale-request",
        turnId: "stale-turn",
        data: {},
      });
      sockets[0].receive({
        type: "delta",
        requestId: "stale-request",
        turnId: "stale-turn",
        data: { content: "旧片段" },
      });
      sockets[0].receive({
        type: "delta",
        requestId,
        turnId: "turn-current",
        data: { content: "尚未 accepted 的片段" },
      });
    });

    expect(result.current.messages).toEqual([
      expect.objectContaining({ role: "user", content: "第一轮" }),
    ]);
    expect(result.current.busy).toBe(true);

    const accepted: AgentWsEnvelope = {
      type: "accepted",
      requestId,
      turnId: "turn-current",
      data: {},
    };
    act(() => {
      sockets[0].receive(accepted);
      sockets[0].receive({
        type: "think",
        requestId: "stale-request",
        turnId: "turn-current",
        data: { content: "旧思考" },
      });
      sockets[0].receive({
        type: "act",
        requestId,
        turnId: "stale-turn",
        data: { tool: "old_tool" },
      });
      sockets[0].receive({
        type: "observe",
        requestId,
        data: { content: "缺少 turn" },
      });
      sockets[0].receive({
        type: "delta",
        requestId,
        turnId: "stale-turn",
        data: { content: "错误 turn" },
      });
      sockets[0].receive({
        type: "final",
        requestId: "stale-request",
        turnId: "turn-current",
        data: { content: "旧最终回答" },
      });
    });

    expect(result.current.liveSteps).toEqual([]);
    expect(result.current.messages).toHaveLength(1);
    expect(result.current.busy).toBe(true);

    act(() => {
      sockets[0].receive({
        type: "think",
        requestId,
        turnId: "turn-current",
        data: { content: "当前思考" },
      });
      sockets[0].receive({
        type: "act",
        requestId,
        turnId: "turn-current",
        data: { tool: "current_tool" },
      });
      sockets[0].receive({
        type: "observe",
        requestId,
        turnId: "turn-current",
        data: { content: "当前观察" },
      });
      sockets[0].receive({
        type: "delta",
        requestId,
        turnId: "turn-current",
        data: { content: "当前片段" },
      });
    });

    expect(result.current.liveSteps).toHaveLength(3);
    expect(result.current.messages.at(-1)).toMatchObject({
      role: "assistant",
      content: "当前片段",
      streaming: true,
    });

    act(() => sockets[0].receive({
      type: "final",
      requestId,
      turnId: "turn-current",
      data: { content: "当前最终回答" },
    }));

    expect(result.current.busy).toBe(false);
    expect(result.current.messages.at(-1)).toMatchObject({
      role: "assistant",
      content: "当前最终回答",
      streaming: false,
      completionState: "done",
    });
  });

  it("handles only the current request-level rejection before accepted", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    await act(async () => result.current.sendMessage("会被前置拒绝的一轮"));
    const { requestId } = sentChatPayload(sockets[0]);

    act(() => {
      sockets[0].receive({
        type: "rejected",
        requestId: "stale-request",
        data: { code: "TURN_IN_PROGRESS", message: "旧拒绝" },
      });
      sockets[0].receive({
        type: "error",
        requestId,
        data: { message: "没有 turn 的 error" },
      });
    });

    expect(result.current.busy).toBe(true);
    expect(result.current.messages).toHaveLength(1);

    act(() => sockets[0].receive({
      type: "rejected",
      requestId,
      data: {
        code: "TURN_IN_PROGRESS",
        message: "当前会话已有请求正在处理。",
      },
    }));

    expect(result.current.busy).toBe(false);
    expect(result.current.messages.at(-1)).toMatchObject({
      role: "system",
      content: "当前会话已有请求正在处理。",
    });
  });

  it("ignores stale errors and handles an error from the bound current turn", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    await act(async () => result.current.sendMessage("会失败的一轮"));
    const { requestId } = sentChatPayload(sockets[0]);
    act(() => sockets[0].receive({
      type: "accepted",
      requestId,
      turnId: "turn-error",
      data: {},
    }));

    act(() => {
      sockets[0].receive({
        type: "rejected",
        requestId,
        data: { code: "DUPLICATE_REQUEST", message: "迟到拒绝" },
      });
      sockets[0].receive({
        type: "error",
        requestId,
        turnId: "stale-turn",
        data: { message: "旧错误" },
      });
    });

    expect(result.current.busy).toBe(true);
    expect(result.current.messages).toHaveLength(1);

    act(() => sockets[0].receive({
      type: "error",
      requestId,
      turnId: "turn-error",
      data: { message: "当前错误" },
    }));

    expect(result.current.busy).toBe(false);
    expect(result.current.messages.at(-1)).toMatchObject({
      role: "system",
      content: "当前错误",
    });
  });

  it("preserves a partial assistant reply as interrupted when the current turn errors", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    await act(async () => result.current.sendMessage("生成一半会失败的一轮"));
    const { requestId } = sentChatPayload(sockets[0]);

    act(() => {
      sockets[0].receive({
        type: "accepted",
        requestId,
        turnId: "turn-partial-error",
        data: {},
      });
      sockets[0].receive({
        type: "delta",
        requestId,
        turnId: "turn-partial-error",
        data: { content: "- **尚未完成的回答" },
      });
      sockets[0].receive({
        type: "error",
        requestId,
        turnId: "turn-partial-error",
        data: { message: "当前错误" },
      });
    });

    expect(result.current.busy).toBe(false);
    expect(result.current.messages).toEqual([
      expect.objectContaining({ role: "user", content: "生成一半会失败的一轮" }),
      expect.objectContaining({
        role: "assistant",
        content: "- **尚未完成的回答",
        streaming: false,
        completionState: "interrupted",
      }),
      expect.objectContaining({ role: "system", content: "当前错误" }),
    ]);
  });

  it("keeps cleared envelopes compatible without request and turn ids", async () => {
    const { result } = renderAgentHook([
      { id: "existing", role: "assistant", content: "已有消息" },
    ]);
    await waitFor(() => expect(sockets).toHaveLength(1));

    act(() => result.current.clearConversation());
    act(() => sockets[0].receive({ type: "cleared", data: {} }));

    expect(result.current.messages).toEqual([]);
    expect(result.current.busy).toBe(false);
  });

  it("does not let an unknown or backend clear acknowledgement reset the current turn", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    await act(async () => result.current.sendMessage("当前轮"));
    const { requestId } = sentChatPayload(sockets[0]);

    act(() => sockets[0].receive({ type: "cleared", data: {} }));
    expect(result.current.busy).toBe(true);

    act(() => {
      result.current.clearBackendMemory("another-session");
      sockets[0].receive({ type: "cleared", data: {} });
    });
    expect(result.current.busy).toBe(true);

    act(() => {
      sockets[0].receive({
        type: "accepted",
        requestId,
        turnId: "turn-after-clear",
        data: {},
      });
      sockets[0].receive({
        type: "final",
        requestId,
        turnId: "turn-after-clear",
        data: { content: "仍然完成" },
      });
    });

    expect(result.current.busy).toBe(false);
    expect(result.current.messages.at(-1)).toMatchObject({
      role: "assistant",
      content: "仍然完成",
    });
  });

  it("ignores a user clear acknowledgement that arrives after a new turn starts", async () => {
    const { result } = renderAgentHook([
      { id: "old", role: "assistant", content: "旧消息" },
    ]);
    await waitFor(() => expect(sockets).toHaveLength(1));

    act(() => result.current.clearConversation());
    await act(async () => result.current.sendMessage("清理后新问题"));
    const { requestId } = sentChatPayload(sockets[0], 1);
    act(() => sockets[0].receive({ type: "cleared", data: {} }));

    expect(result.current.busy).toBe(true);
    expect(result.current.messages).toEqual([
      { id: "old", role: "assistant", content: "旧消息" },
      expect.objectContaining({ role: "user", content: "清理后新问题" }),
    ]);

    act(() => {
      sockets[0].receive({
        type: "accepted",
        requestId,
        turnId: "turn-new-after-clear",
        data: {},
      });
      sockets[0].receive({
        type: "final",
        requestId,
        turnId: "turn-new-after-clear",
        data: { content: "新回答" },
      });
    });
    expect(result.current.messages.at(-1)).toMatchObject({
      role: "assistant",
      content: "新回答",
    });
  });

  it("does not erase a newer completed turn when a user clear acknowledgement is late", async () => {
    const { result } = renderAgentHook([
      { id: "old", role: "assistant", content: "旧消息" },
    ]);
    await waitFor(() => expect(sockets).toHaveLength(1));

    act(() => result.current.clearConversation());
    const clearPayload = JSON.parse(String(sockets[0].send.mock.calls[0]?.[0])) as {
      requestId: string;
    };
    await act(async () => result.current.sendMessage("新一轮"));
    const { requestId } = sentChatPayload(sockets[0], 1);
    act(() => {
      sockets[0].receive({
        type: "accepted",
        requestId,
        turnId: "turn-completed-before-clear",
        data: {},
      });
      sockets[0].receive({
        type: "final",
        requestId,
        turnId: "turn-completed-before-clear",
        data: { content: "新回答已完成" },
      });
      sockets[0].receive({
        type: "cleared",
        requestId: clearPayload.requestId,
        data: {},
      });
    });

    expect(result.current.messages).toContainEqual(expect.objectContaining({
      role: "assistant",
      content: "新回答已完成",
    }));
  });

  it("correlates overlapping clear acknowledgements without overwriting intent", async () => {
    const { result } = renderAgentHook([
      { id: "existing", role: "assistant", content: "待清理" },
    ]);
    await waitFor(() => expect(sockets).toHaveLength(1));

    act(() => {
      result.current.clearConversation();
      result.current.clearBackendMemory("another-session");
    });
    const userClear = JSON.parse(String(sockets[0].send.mock.calls[0]?.[0])) as {
      requestId: string;
    };
    const backendClear = JSON.parse(String(sockets[0].send.mock.calls[1]?.[0])) as {
      requestId: string;
    };
    expect(userClear.requestId).not.toBe(backendClear.requestId);

    act(() => {
      sockets[0].receive({ type: "cleared", requestId: backendClear.requestId, data: {} });
    });
    expect(result.current.messages).toHaveLength(1);

    act(() => {
      sockets[0].receive({ type: "cleared", requestId: userClear.requestId, data: {} });
    });
    expect(result.current.messages).toEqual([]);
  });

  it("ignores matching ids when rejected or turn events arrive from an old socket", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    await act(async () => result.current.sendMessage("旧连接上的第一轮"));
    act(() => sockets[0].fail());

    await act(async () => result.current.sendMessage("新连接上的第二轮"));
    expect(sockets).toHaveLength(2);
    const { requestId } = sentChatPayload(sockets[1]);

    act(() => sockets[0].receive({
      type: "rejected",
      requestId,
      data: { code: "TURN_IN_PROGRESS", message: "旧 socket 拒绝" },
    }));
    expect(result.current.busy).toBe(true);
    expect(result.current.messages.some((message) => (
      message.content === "旧 socket 拒绝"
    ))).toBe(false);

    act(() => {
      sockets[0].receive({
        type: "accepted",
        requestId,
        turnId: "turn-from-old-socket",
        data: {},
      });
      sockets[0].receive({
        type: "delta",
        requestId,
        turnId: "turn-from-old-socket",
        data: { content: "旧 socket 片段" },
      });
      sockets[0].receive({
        type: "final",
        requestId,
        turnId: "turn-from-old-socket",
        data: { content: "旧 socket 最终回答" },
      });
    });
    expect(result.current.busy).toBe(true);
    expect(result.current.messages.some((message) => (
      message.content.includes("旧 socket") && message.role === "assistant"
    ))).toBe(false);

    act(() => {
      sockets[1].receive({
        type: "accepted",
        requestId,
        turnId: "turn-from-current-socket",
        data: {},
      });
      sockets[1].receive({
        type: "final",
        requestId,
        turnId: "turn-from-current-socket",
        data: { content: "当前 socket 最终回答" },
      });
    });
    expect(result.current.busy).toBe(false);
    expect(result.current.messages.at(-1)).toMatchObject({
      role: "assistant",
      content: "当前 socket 最终回答",
    });
  });

  it("ignores proactive notifications delivered by a replaced socket", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    await act(async () => result.current.sendMessage("第一轮"));
    act(() => sockets[0].fail());
    await act(async () => result.current.sendMessage("第二轮"));
    expect(sockets).toHaveLength(2);

    const notification = {
      type: "proactive",
      data: { type: "thought", message: "只能来自当前连接" },
    };
    act(() => sockets[0].receive(notification));
    expect(result.current.proactiveNotifications).toEqual([]);

    act(() => sockets[1].receive(notification));
    expect(result.current.proactiveNotifications).toHaveLength(1);
  });

  it("does not let an earlier final timer reset a newer turn phase", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    vi.useFakeTimers();
    await act(async () => result.current.sendMessage("第一轮"));
    const first = sentChatPayload(sockets[0]);
    act(() => {
      sockets[0].receive({
        type: "accepted",
        requestId: first.requestId,
        turnId: "turn-with-old-timer",
        data: {},
      });
      sockets[0].receive({
        type: "final",
        requestId: first.requestId,
        turnId: "turn-with-old-timer",
        data: { content: "第一轮完成" },
      });
    });

    await act(async () => result.current.sendMessage("第二轮"));
    const second = sentChatPayload(sockets[0], 1);
    act(() => {
      sockets[0].receive({
        type: "accepted",
        requestId: second.requestId,
        turnId: "turn-after-old-timer",
        data: {},
      });
      sockets[0].receive({
        type: "think",
        requestId: second.requestId,
        turnId: "turn-after-old-timer",
        data: { content: "新一轮正在思考" },
      });
      vi.advanceTimersByTime(2500);
    });

    expect(result.current.livePhase).toBe("think");
  });
});

describe("useAgentWebSocket disconnect recovery", () => {
  beforeEach(() => {
    sockets = [];
    socketReadyStates = [];
    vi.mocked(getAgentWsUrl).mockReset().mockResolvedValue("ws://example.test/api/v1/agent/ws");
    vi.stubGlobal("WebSocket", MockWebSocket);
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it.each(["close", "error"] as const)(
    "interrupts a busy turn on socket %s and reconnects with a fresh request",
    async (eventType) => {
      const { result } = renderAgentHook();
      await waitFor(() => expect(sockets).toHaveLength(1));
      await act(async () => result.current.sendMessage("断线前的问题"));
      const firstPayload = sentChatPayload(sockets[0]);
      act(() => {
        sockets[0].receive({
          type: "accepted",
          requestId: firstPayload.requestId,
          turnId: "turn-disconnect",
          data: {},
        });
        sockets[0].receive({
          type: "think",
          requestId: firstPayload.requestId,
          turnId: "turn-disconnect",
          data: { content: "已经开始思考" },
        });
        sockets[0].receive({
          type: "delta",
          requestId: firstPayload.requestId,
          turnId: "turn-disconnect",
          data: { content: "已经收到的回答" },
        });
      });

      act(() => {
        if (eventType === "close") sockets[0].disconnect();
        else sockets[0].fail();
      });

      expect(result.current.busy).toBe(false);
      expect(result.current.liveSteps).toEqual([]);
      expect(result.current.messages).toEqual([
        expect.objectContaining({ role: "user", content: "断线前的问题" }),
        expect.objectContaining({
          role: "assistant",
          content: "已经收到的回答",
          streaming: false,
          completionState: "interrupted",
        }),
        expect.objectContaining({
          role: "system",
          content: "Agent 连接已中断，请重新发送。",
        }),
      ]);

      act(() => sockets[0].receive({
        type: "delta",
        requestId: firstPayload.requestId,
        turnId: "turn-disconnect",
        data: { content: "迟到片段" },
      }));
      expect(result.current.messages[1].content).toBe("已经收到的回答");

      await act(async () => result.current.sendMessage("重新发送"));
      expect(sockets).toHaveLength(2);
      const secondPayload = sentChatPayload(sockets[1]);
      expect(secondPayload.requestId).not.toBe(firstPayload.requestId);
      expect(result.current.busy).toBe(true);
    },
  );

  it("abandons the current turn before session migration and ignores its late terminal events", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    await act(async () => result.current.sendMessage("切换前的问题"));
    const { requestId } = sentChatPayload(sockets[0]);
    act(() => {
      sockets[0].receive({
        type: "accepted",
        requestId,
        turnId: "turn-before-migration",
        data: {},
      });
      sockets[0].receive({
        type: "delta",
        requestId,
        turnId: "turn-before-migration",
        data: { content: "- **切换前的部分回答" },
      });
    });

    act(() => result.current.abandonCurrentTurn());

    expect(result.current.busy).toBe(false);
    expect(sockets[0].close).toHaveBeenCalledOnce();
    expect(result.current.messages.at(-1)).toMatchObject({
      role: "assistant",
      content: "- **切换前的部分回答",
      streaming: false,
      completionState: "interrupted",
    });

    act(() => {
      sockets[0].receive({
        type: "final",
        requestId,
        turnId: "turn-before-migration",
        data: { content: "不应写入的迟到 final" },
      });
      sockets[0].receive({
        type: "error",
        requestId,
        turnId: "turn-before-migration",
        data: { message: "不应写入的迟到 error" },
      });
    });

    expect(result.current.messages.some((message) => message.content.includes("不应写入"))).toBe(false);
  });

  it("invalidates a send waiting for a ticket without letting its finally unlock the next attempt", async () => {
    let resolveOldTicket!: (url: string) => void;
    let resolveNewTicket!: (url: string) => void;
    const oldTicket = new Promise<string>((resolve) => { resolveOldTicket = resolve; });
    const newTicket = new Promise<string>((resolve) => { resolveNewTicket = resolve; });
    vi.mocked(getAgentWsUrl)
      .mockReset()
      .mockImplementationOnce(() => oldTicket)
      .mockImplementationOnce(() => newTicket);
    const { result, activeSessionIdRef } = renderAgentHook();
    await waitFor(() => expect(getAgentWsUrl).toHaveBeenCalledOnce());

    let oldSend!: Promise<void>;
    act(() => { oldSend = result.current.sendMessage("旧会话问题"); });
    act(() => {
      result.current.abandonCurrentTurn();
      activeSessionIdRef.current = "session-2";
    });

    let newSend!: Promise<void>;
    act(() => { newSend = result.current.sendMessage("新会话问题"); });
    await waitFor(() => expect(getAgentWsUrl).toHaveBeenCalledTimes(2));

    await act(async () => {
      resolveOldTicket("ws://example.test/api/v1/agent/ws?ticket=old");
      await oldSend;
    });
    await act(async () => result.current.sendMessage("不应抢占新 attempt"));
    expect(getAgentWsUrl).toHaveBeenCalledTimes(2);

    await act(async () => {
      resolveNewTicket("ws://example.test/api/v1/agent/ws?ticket=new");
      await newSend;
    });

    expect(sockets).toHaveLength(1);
    expect(sockets[0].send).toHaveBeenCalledOnce();
    expect(JSON.parse(String(sockets[0].send.mock.calls[0]?.[0]))).toMatchObject({
      type: "chat",
      sessionId: "session-2",
      message: "新会话问题",
    });
    expect(result.current.messages).toEqual([
      expect.objectContaining({ role: "user", content: "新会话问题" }),
    ]);
  });

  it("closes a connecting socket and lets the migrated session send immediately", async () => {
    const { result, activeSessionIdRef } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    act(() => sockets[0].disconnect());
    socketReadyStates.push(MockWebSocket.CONNECTING);

    let oldSend!: Promise<void>;
    act(() => { oldSend = result.current.sendMessage("握手中的旧问题"); });
    await waitFor(() => expect(sockets).toHaveLength(2));

    act(() => {
      result.current.abandonCurrentTurn();
      activeSessionIdRef.current = "session-2";
    });
    expect(sockets[1].close).toHaveBeenCalledOnce();

    await act(async () => result.current.sendMessage("迁移后的新问题"));
    expect(sockets).toHaveLength(3);
    expect(JSON.parse(String(sockets[2].send.mock.calls[0]?.[0]))).toMatchObject({
      type: "chat",
      sessionId: "session-2",
      message: "迁移后的新问题",
    });

    act(() => sockets[1].disconnect());
    await act(async () => oldSend);
    expect(sockets[1].send).not.toHaveBeenCalled();
    expect(result.current.messages.some((message) => (
      message.content.includes("旧问题") || message.content.includes("连接失败")
    ))).toBe(false);
  });

  it("rolls back the turn when socket.send throws and allows a fresh retry", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    sockets[0].send.mockImplementationOnce(() => {
      throw new Error("socket closed during send");
    });

    await act(async () => result.current.sendMessage("发送时断开"));
    const firstPayload = sentChatPayload(sockets[0]);

    expect(result.current.busy).toBe(false);
    expect(result.current.liveSteps).toEqual([]);
    expect(result.current.messages).toEqual([
      expect.objectContaining({ role: "user", content: "发送时断开" }),
      expect.objectContaining({
        role: "system",
        content: "Agent 连接已中断，请重新发送。",
      }),
    ]);

    await act(async () => result.current.sendMessage("重新发送成功"));
    expect(sockets).toHaveLength(2);
    const secondPayload = sentChatPayload(sockets[1]);
    expect(secondPayload.requestId).not.toBe(firstPayload.requestId);
    expect(result.current.busy).toBe(true);
  });

  it("adds the interruption prompt only once when error is followed by close", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    await act(async () => result.current.sendMessage("幂等中断"));

    act(() => {
      sockets[0].fail();
      sockets[0].disconnect();
    });

    expect(result.current.messages.filter((message) => (
      message.role === "system" && message.content === "Agent 连接已中断，请重新发送。"
    ))).toHaveLength(1);
  });

  it("does not let a late close from the failed socket interrupt the reconnected turn", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    await act(async () => result.current.sendMessage("第一轮"));

    act(() => sockets[0].fail());
    await act(async () => result.current.sendMessage("第二轮"));
    expect(sockets).toHaveLength(2);
    expect(result.current.busy).toBe(true);

    act(() => sockets[0].disconnect());

    expect(result.current.busy).toBe(true);
    expect(result.current.messages.filter((message) => (
      message.role === "system" && message.content === "Agent 连接已中断，请重新发送。"
    ))).toHaveLength(1);
  });

  it("releases a reconnect attempt when the connecting socket closes before open", async () => {
    const { result } = renderAgentHook();
    await waitFor(() => expect(sockets).toHaveLength(1));
    act(() => sockets[0].disconnect());
    socketReadyStates.push(MockWebSocket.CONNECTING);

    let settled = false;
    let pendingSend!: Promise<void>;
    act(() => {
      pendingSend = result.current.sendMessage("握手中的请求");
      void pendingSend.then(
        () => { settled = true; },
        () => { settled = true; },
      );
    });
    await waitFor(() => expect(sockets).toHaveLength(2));

    act(() => sockets[1].disconnect());
    await waitFor(() => expect(settled).toBe(true));
    await act(async () => pendingSend);

    expect(sockets[1].send).not.toHaveBeenCalled();
    expect(result.current.busy).toBe(false);
    expect(result.current.messages).toEqual([
      expect.objectContaining({
        role: "system",
        content: "Agent 连接失败，请确认后端已启动且 LLM_ENABLED=true。",
      }),
    ]);
  });

  it("shares a pending preconnection instead of letting a late socket overwrite the active one", async () => {
    let resolveUrl!: (url: string) => void;
    const pendingUrl = new Promise<string>((resolve) => {
      resolveUrl = resolve;
    });
    vi.mocked(getAgentWsUrl).mockReset().mockImplementation(() => pendingUrl);
    const { result } = renderAgentHook();
    await waitFor(() => expect(getAgentWsUrl).toHaveBeenCalledOnce());

    let pendingSend!: Promise<void>;
    act(() => {
      pendingSend = result.current.sendMessage("等待预连接");
    });

    expect(getAgentWsUrl).toHaveBeenCalledOnce();
    await act(async () => {
      resolveUrl("ws://example.test/api/v1/agent/ws");
      await pendingSend;
    });
    expect(sockets).toHaveLength(1);
    const firstPayload = sentChatPayload(sockets[0]);

    act(() => {
      sockets[0].receive({
        type: "accepted",
        requestId: firstPayload.requestId,
        turnId: "turn-shared-connection",
        data: {},
      });
      sockets[0].receive({
        type: "final",
        requestId: firstPayload.requestId,
        turnId: "turn-shared-connection",
        data: { content: "第一轮完成" },
      });
    });
    await act(async () => result.current.sendMessage("继续复用连接"));

    expect(sockets).toHaveLength(1);
    expect(sockets[0].send).toHaveBeenCalledTimes(2);
    const secondPayload = sentChatPayload(sockets[0], 1);
    expect(secondPayload.requestId).not.toBe(firstPayload.requestId);
  });

  it("does not create a socket when the ticket resolves after unmount", async () => {
    let resolveUrl!: (url: string) => void;
    const pendingUrl = new Promise<string>((resolve) => {
      resolveUrl = resolve;
    });
    vi.mocked(getAgentWsUrl).mockReset().mockImplementation(() => pendingUrl);
    const { unmount } = renderAgentHook();
    await waitFor(() => expect(getAgentWsUrl).toHaveBeenCalledOnce());

    unmount();
    await act(async () => {
      resolveUrl("ws://example.test/api/v1/agent/ws");
      await pendingUrl;
      await Promise.resolve();
    });

    expect(sockets).toHaveLength(0);
  });

  it.each(["close", "error"] as const)(
    "does not append an interruption prompt for an idle socket %s",
    async (eventType) => {
      const { result } = renderAgentHook([
        { id: "existing", role: "assistant", content: "已有消息" },
      ]);
      await waitFor(() => expect(sockets).toHaveLength(1));

      act(() => {
        if (eventType === "close") sockets[0].disconnect();
        else sockets[0].fail();
      });

      expect(result.current.messages).toEqual([
        { id: "existing", role: "assistant", content: "已有消息" },
      ]);
      expect(result.current.busy).toBe(false);
    },
  );
});
