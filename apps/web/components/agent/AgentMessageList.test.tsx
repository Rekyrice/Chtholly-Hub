import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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
