import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import AgentSessionSidebar from "@/components/agent/AgentSessionSidebar";

vi.mock("@/components/agent/AgentChatProvider", () => ({
  useAgentChatContext: () => ({
    sessions: [{
      id: "active-session",
      title: "正在回答的会话",
      messages: [],
      createdAt: 1,
      updatedAt: 2,
    }],
    activeSessionId: "active-session",
    busy: true,
    switchSession: vi.fn(),
    createSession: vi.fn(),
    renameSession: vi.fn(),
    deleteSession: vi.fn(),
  }),
}));

describe("AgentSessionSidebar", () => {
  it("disables deleting the active session while its turn is busy", () => {
    render(
      <AgentSessionSidebar
        collapsed={false}
        onToggleCollapse={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "会话操作" }));

    expect(screen.getByRole("menuitem", { name: "删除" })).toBeDisabled();
  });
});
