import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AgentChatPanel from "@/components/agent/AgentChatPanel";

const agentState = vi.hoisted(() => ({ busy: false, streaming: false }));
const linkState = vi.hoisted(() => ({ componentPreventedNavigation: false }));

vi.mock("next/link", () => ({
  default: ({ children, onClick, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a
      {...props}
      onClick={(event) => {
        onClick?.(event);
        linkState.componentPreventedNavigation = event.defaultPrevented;
        event.preventDefault();
      }}
    >
      {children}
    </a>
  ),
}));
vi.mock("@/components/agent/AgentChatProvider", () => ({
  useAgentChatContext: () => ({
    activeSessionId: "session-1",
    messages: [],
    input: "",
    setInput: vi.fn(),
    connected: true,
    busy: agentState.busy,
    streaming: agentState.streaming,
    showSteps: false,
    setShowSteps: vi.fn(),
    richMarkdown: false,
    liveSteps: [],
    sendMessage: vi.fn(),
    clearConversation: vi.fn(),
    fillAndSend: vi.fn(),
  }),
}));
vi.mock("@/components/agent/AgentMessageList", () => ({ default: () => <div /> }));
vi.mock("@/components/agent/AgentWorkspaceSettings", () => ({ default: () => <div /> }));
vi.mock("@/lib/hooks/useAgentPlaceholder", () => ({ useAgentPlaceholder: () => "placeholder" }));
vi.mock("@/lib/hooks/useMinWidth", () => ({ useMinWidth: () => true }));

describe("AgentChatPanel expansion", () => {
  beforeEach(() => {
    agentState.busy = false;
    agentState.streaming = false;
    linkState.componentPreventedNavigation = false;
  });

  afterEach(() => {
    cleanup();
  });

  it.each([
    [true, false],
    [false, true],
  ])("blocks expansion while busy=%s and streaming=%s", (busy, streaming) => {
    agentState.busy = busy;
    agentState.streaming = streaming;
    const onExpand = vi.fn();
    const { container } = render(<AgentChatPanel variant="float" onExpand={onExpand} />);
    const expandLink = container.querySelector<HTMLAnchorElement>('a[href^="/agent?session="]');

    expect(expandLink).not.toBeNull();
    expect(expandLink).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(expandLink!);
    expect(linkState.componentPreventedNavigation).toBe(true);
    expect(onExpand).not.toHaveBeenCalled();
  });

  it("keeps the idle expansion behavior", () => {
    const onExpand = vi.fn();
    const { container } = render(<AgentChatPanel variant="float" onExpand={onExpand} />);
    const expandLink = container.querySelector<HTMLAnchorElement>('a[href^="/agent?session="]');

    expect(expandLink).not.toBeNull();
    expect(expandLink).toHaveAttribute("aria-disabled", "false");
    fireEvent.click(expandLink!);
    expect(linkState.componentPreventedNavigation).toBe(false);
    expect(onExpand).toHaveBeenCalledTimes(1);
  });

  it("uses Chtholly4 as the compact chat avatar", () => {
    render(<AgentChatPanel variant="room" />);

    expect(screen.getByTestId("chtholly-avatar")).toHaveAttribute("data-size", "md");
  });
});
