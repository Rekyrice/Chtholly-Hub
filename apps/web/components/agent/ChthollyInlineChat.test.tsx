import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import ChthollyInlineChat from "@/components/agent/ChthollyInlineChat";

vi.mock("next/link", () => ({
  default: ({ children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a {...props}>{children}</a>
  ),
}));

vi.mock("@/components/agent/AgentChatProvider", () => ({
  useAgentChatContext: () => ({
    activeSessionId: "room-session",
    loggedIn: true,
  }),
}));

vi.mock("@/components/agent/AgentChatPanel", () => ({
  default: ({
    headerAction,
    onMinimize,
    variant,
  }: {
    headerAction?: React.ReactNode;
    onMinimize?: () => void;
    variant?: string;
  }) => (
    <div data-testid="inline-agent-panel" data-variant={variant}>
      {headerAction}
      <button type="button" onClick={onMinimize}>收起</button>
    </div>
  ),
}));

describe("ChthollyInlineChat", () => {
  afterEach(cleanup);

  it("shows one clear chat action before the conversation opens", () => {
    render(<ChthollyInlineChat />);

    expect(screen.getAllByRole("button", { name: "和珂朵莉聊天" })).toHaveLength(1);
    expect(screen.queryByRole("link", { name: "全屏打开" })).not.toBeInTheDocument();
    expect(screen.queryByTestId("inline-agent-panel")).not.toBeInTheDocument();
  });

  it("expands inline and only then exposes the full workspace link", () => {
    render(<ChthollyInlineChat />);

    fireEvent.click(screen.getByRole("button", { name: "和珂朵莉聊天" }));

    expect(screen.getByTestId("inline-agent-panel")).toHaveAttribute("data-variant", "room");
    expect(screen.getByRole("link", { name: "全屏打开" })).toHaveAttribute(
      "href",
      "/agent?session=room-session",
    );
    expect(screen.queryByRole("button", { name: "和珂朵莉聊天" })).not.toBeInTheDocument();
  });
});
