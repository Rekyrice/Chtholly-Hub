import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AgentMessageList from "@/components/agent/AgentMessageList";
import type { ChatMessage } from "@/lib/types/agent";

vi.mock("@/lib/hooks/useMangaMessageScroll", () => ({ useMangaMessageScroll: vi.fn() }));

function rowFor(content: string) {
  const row = screen.getByText(content).closest(".agent-message-row");
  if (!row) throw new Error(`Missing row for ${content}`);
  return row;
}

describe("AgentMessageList enter animation", () => {
  beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn();
  });

  it("enters once per stable message id and clears the class after animation", async () => {
    const first: ChatMessage = { id: "first", role: "user", content: "第一条" };
    const { rerender } = render(
      <AgentMessageList messages={[first]} busy={false} showSteps={false} liveSteps={[]} />,
    );

    expect(rowFor("第一条")).toHaveClass("agent-message-row--user-enter");
    // jsdom exposes WebkitAnimation without AnimationEvent, so React registers the prefixed event.
    fireEvent(rowFor("第一条"), new window.Event("webkitAnimationEnd", { bubbles: true }));
    await waitFor(() => expect(rowFor("第一条")).not.toHaveClass("agent-message-row--user-enter"));

    rerender(
      <AgentMessageList messages={[{ ...first, content: "第一条更新" }]} busy={false} showSteps={false} liveSteps={[]} />,
    );
    expect(rowFor("第一条更新")).not.toHaveClass("agent-message-row--user-enter");

    const second: ChatMessage = { id: "second", role: "user", content: "第二条" };
    rerender(
      <AgentMessageList messages={[first, second]} busy={false} showSteps={false} liveSteps={[]} />,
    );
    expect(rowFor("第一条")).not.toHaveClass("agent-message-row--user-enter");
    expect(rowFor("第二条")).toHaveClass("agent-message-row--user-enter");
  });

  it("keeps non-workspace auto-scroll inside the supplied message container", () => {
    const container = document.createElement("div");
    container.scrollTo = vi.fn();
    Object.defineProperty(container, "scrollHeight", { value: 640 });
    const scrollContainerRef = { current: container };
    const message: ChatMessage = {
      id: "streaming-reply",
      role: "assistant",
      content: "正在生成回复",
      streaming: true,
    };

    render(
      <AgentMessageList
        messages={[message]}
        busy
        showSteps={false}
        liveSteps={[]}
        scrollContainerRef={scrollContainerRef}
      />,
    );

    expect(container.scrollTo).toHaveBeenCalledWith({
      top: 640,
      behavior: "smooth",
    });
    expect(Element.prototype.scrollIntoView).not.toHaveBeenCalled();
  });
});

describe("AgentMessageList compact assistant replies", () => {
  beforeEach(() => {
    Element.prototype.scrollIntoView = vi.fn();
  });

  it("lets users expand and collapse a completed long reply", () => {
    const content = "很长的回答。".repeat(80);
    const message: ChatMessage = { id: "long-reply", role: "assistant", content };

    render(
      <AgentMessageList
        messages={[message]}
        busy={false}
        showSteps={false}
        liveSteps={[]}
        compactAssistantMessages
      />,
    );

    const text = screen.getByText(content);
    expect(text).toHaveClass("agent-bubble-content--collapsed");
    const expand = screen.getByRole("button", { name: "展开回答" });
    expect(expand).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(expand);

    expect(text).not.toHaveClass("agent-bubble-content--collapsed");
    expect(screen.getByRole("button", { name: "收起回答" })).toHaveAttribute("aria-expanded", "true");
  });

  it("does not collapse short replies", () => {
    const messages: ChatMessage[] = [
      { id: "short", role: "assistant", content: "简短回答" },
    ];

    render(
      <AgentMessageList
        messages={messages}
        busy={false}
        showSteps={false}
        liveSteps={[]}
        compactAssistantMessages
      />,
    );

    expect(screen.queryByRole("button", { name: "展开回答" })).not.toBeInTheDocument();
  });

  it("bounds a long streaming reply as soon as it exceeds the compact threshold", () => {
    const content = "生成中".repeat(200);
    const message: ChatMessage = {
      id: "streaming",
      role: "assistant",
      content,
      streaming: true,
    };

    render(
      <AgentMessageList
        messages={[message]}
        busy
        showSteps={false}
        liveSteps={[]}
        compactAssistantMessages
      />,
    );

    expect(screen.getByText(content)).toHaveClass("agent-bubble-content--collapsed");
    expect(screen.getByRole("button", { name: "展开回答" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );
  });
});

describe("AgentMessageList rich assistant replies", () => {
  it("keeps a rich reply as plain text while streaming and renders Markdown after completion", () => {
    const content = "- **证据一**\n- 证据二";
    const streamingMessage: ChatMessage = {
      id: "streaming-rich-reply",
      role: "assistant",
      content,
      streaming: true,
    };
    const { container, rerender, unmount } = render(
      <AgentMessageList
        messages={[streamingMessage]}
        busy
        showSteps={false}
        liveSteps={[]}
        rich
      />,
    );

    const bubble = container.querySelector<HTMLElement>(".agent-bubble-assistant");
    expect(bubble?.textContent).toContain("- **证据一**");
    expect(bubble?.querySelector("ul")).toBeNull();
    expect(bubble?.querySelector("strong")).toBeNull();

    rerender(
      <AgentMessageList
        messages={[{ ...streamingMessage, streaming: false }]}
        busy={false}
        showSteps={false}
        liveSteps={[]}
        rich
      />,
    );

    expect(within(bubble!).getByRole("list")).toBeInTheDocument();
    expect(within(bubble!).getByText("证据一").closest("strong")).not.toBeNull();
    unmount();
  });

  it("does not turn raw script HTML into DOM when rendering completed Markdown", () => {
    const message: ChatMessage = {
      id: "safe-rich-reply",
      role: "assistant",
      content: "<script>window.__markdownInjected = true</script>\n\n**安全强调**",
    };

    const { container, unmount } = render(
      <AgentMessageList
        messages={[message]}
        busy={false}
        showSteps={false}
        liveSteps={[]}
        rich
      />,
    );

    expect(container.querySelector("script")).toBeNull();
    expect(screen.getByText("安全强调").closest("strong")).not.toBeNull();
    unmount();
  });

  it("marks completed markdown replies with the reading typography variant", () => {
    const message: ChatMessage = {
      id: "rich-reply",
      role: "assistant",
      content: "第一段。\n\n- 证据一\n- 证据二",
    };

    render(
      <AgentMessageList
        messages={[message]}
        busy={false}
        showSteps={false}
        liveSteps={[]}
        rich
      />,
    );

    const bubble = screen.getByText("第一段。").closest(".agent-bubble-assistant");
    expect(bubble).toHaveClass("agent-bubble-assistant--rich");
    expect(screen.getByRole("list")).toBeInTheDocument();
  });

  it("collapses and expands a completed long rich reply without losing markdown semantics", () => {
    const content = [
      "**结论**",
      "",
      "这是完成态富文本回答。".repeat(40),
      "",
      "- 证据一",
      "- 证据二",
    ].join("\n");
    const message: ChatMessage = {
      id: "long-rich-reply",
      role: "assistant",
      content,
    };

    render(
      <AgentMessageList
        messages={[message]}
        busy={false}
        showSteps={false}
        liveSteps={[]}
        rich
        compactAssistantMessages
      />,
    );

    const emphasis = screen.getByText("结论");
    const bubble = emphasis.closest<HTMLElement>(".agent-bubble-assistant");
    const contentContainer = emphasis.closest<HTMLElement>(".agent-bubble-content");
    expect(bubble).not.toBeNull();
    expect(contentContainer).toHaveClass("agent-bubble-content--collapsed");
    expect(emphasis.closest("strong")).not.toBeNull();
    expect(within(bubble!).getByRole("list")).toBeInTheDocument();

    fireEvent.click(within(bubble!).getByRole("button", { name: "展开回答" }));

    expect(contentContainer).not.toHaveClass("agent-bubble-content--collapsed");
    expect(within(bubble!).getByRole("button", { name: "收起回答" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
  });
});
