import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AgentChatPanel from "@/components/agent/AgentChatPanel";

const agentState = vi.hoisted(() => ({
  busy: false,
  turnActive: false,
  streaming: false,
  input: "",
  richMarkdown: false,
  sendMessage: vi.fn(),
}));
const linkState = vi.hoisted(() => ({ componentPreventedNavigation: false }));
const messageListState = vi.hoisted(() => ({
  compactAssistantMessages: undefined as boolean | undefined,
  rich: undefined as boolean | undefined,
}));

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
    input: agentState.input,
    setInput: vi.fn(),
    connected: true,
    busy: agentState.busy,
    turnActive: agentState.turnActive,
    sessionOperationPending: false,
    conversationClearError: null,
    streaming: agentState.streaming,
    showSteps: false,
    setShowSteps: vi.fn(),
    richMarkdown: agentState.richMarkdown,
    liveSteps: [],
    sendMessage: agentState.sendMessage,
    clearConversation: vi.fn(),
    fillAndSend: vi.fn(),
  }),
}));
vi.mock("@/components/agent/AgentMessageList", () => ({
  default: (props: { compactAssistantMessages?: boolean; rich?: boolean }) => {
    messageListState.compactAssistantMessages = props.compactAssistantMessages;
    messageListState.rich = props.rich;
    return <div />;
  },
}));
vi.mock("@/components/agent/AgentWorkspaceSettings", () => ({ default: () => <div /> }));
vi.mock("@/lib/hooks/useAgentPlaceholder", () => ({ useAgentPlaceholder: () => "placeholder" }));
vi.mock("@/lib/hooks/useMinWidth", () => ({ useMinWidth: () => true }));

describe("AgentChatPanel expansion", () => {
  beforeEach(() => {
    agentState.busy = false;
    agentState.turnActive = false;
    agentState.streaming = false;
    agentState.input = "";
    agentState.richMarkdown = false;
    agentState.sendMessage.mockReset();
    linkState.componentPreventedNavigation = false;
    messageListState.compactAssistantMessages = undefined;
    messageListState.rich = undefined;
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

  it("disables navigation and message input while a ticket or socket handshake is pending", () => {
    agentState.turnActive = true;
    agentState.input = "等待发送";
    const { container } = render(<AgentChatPanel variant="float" />);

    expect(container.querySelector('a[href^="/agent?session="]')).toHaveAttribute(
      "aria-disabled",
      "true",
    );
    expect(screen.getByTestId("agent-input")).toBeDisabled();
    expect(screen.getByTestId("agent-send")).toBeDisabled();
  });

  it("uses Chtholly4 as the compact chat avatar", () => {
    render(<AgentChatPanel variant="room" />);

    expect(screen.getByTestId("chtholly-avatar")).toHaveAttribute("data-size", "md");
  });

  it.each([
    ["float", true],
    ["workspace", false],
    ["room", false],
  ] as const)("uses compact replies only for the %s variant", (variant, expected) => {
    render(<AgentChatPanel variant={variant} />);

    expect(messageListState.compactAssistantMessages).toBe(expected);
  });

  it.each([
    [true, "float"],
    [true, "workspace"],
    [true, "room"],
    [false, "float"],
    [false, "workspace"],
    [false, "room"],
  ] as const)("passes rich=%s to messages for the %s variant", (richMarkdown, variant) => {
    agentState.richMarkdown = richMarkdown;
    render(<AgentChatPanel variant={variant} />);

    expect(messageListState.rich).toBe(richMarkdown);
  });

  it.each([
    ["page-explain", "想解释页面里的哪一部分？"],
    ["evidence-outline", "告诉我主题，我会整理一份资料大纲…"],
    ["draft-fact-check", "把要核查的草稿或主张贴在这里…"],
  ] as const)("uses the %s placeholder and sends its explicit task type", (taskType, placeholder) => {
    agentState.input = "解释这里";
    render(
      <AgentChatPanel
        variant="workspace"
        taskType={taskType}
        placeholder={placeholder}
      />,
    );

    expect(screen.getByTestId("agent-input")).toHaveAttribute(
      "placeholder",
      placeholder,
    );
    fireEvent.submit(screen.getByTestId("agent-input").closest("form")!);
    expect(agentState.sendMessage).toHaveBeenCalledWith(
      "解释这里",
      { taskType },
    );
  });
});
