import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AgentChatPanel from "@/components/agent/AgentChatPanel";

vi.mock("next/link", () => ({
  default: ({ children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a {...props}>{children}</a>
  ),
}));
vi.mock("@/components/agent/AgentChatProvider", () => ({
  useAgentChatContext: () => ({
    activeSessionId: "session-1",
    messages: [{
      id: "completed-answer",
      role: "assistant",
      content: "![private diagram](https://tracker.example/panel.png)",
    }],
    input: "",
    setInput: vi.fn(),
    connected: true,
    busy: false,
    streaming: false,
    showSteps: false,
    setShowSteps: vi.fn(),
    richMarkdown: true,
    liveSteps: [],
    sendMessage: vi.fn(),
    clearConversation: vi.fn(),
    fillAndSend: vi.fn(),
  }),
}));
vi.mock("@/components/agent/AgentWorkspaceSettings", () => ({ default: () => <div /> }));
vi.mock("@/lib/hooks/useAgentPlaceholder", () => ({ useAgentPlaceholder: () => "placeholder" }));
vi.mock("@/lib/hooks/useMangaMessageScroll", () => ({ useMangaMessageScroll: vi.fn() }));
vi.mock("@/lib/hooks/useMinWidth", () => ({ useMinWidth: () => true }));

describe("AgentChatPanel safe Markdown", () => {
  beforeEach(() => {
    HTMLElement.prototype.scrollTo = vi.fn();
  });

  afterEach(() => {
    cleanup();
  });

  it.each(["float", "workspace", "room"] as const)(
    "blocks answer images in the %s variant",
    (variant) => {
      const { container } = render(<AgentChatPanel variant={variant} />);
      const richAnswer = container.querySelector<HTMLElement>(".agent-rich-markdown");

      expect(richAnswer).not.toBeNull();
      expect(richAnswer?.querySelector("img")).toBeNull();
      expect(richAnswer).toHaveTextContent("private diagram");
    },
  );
});
