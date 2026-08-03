import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AgentSessionSidebar from "@/components/agent/AgentSessionSidebar";

const mocks = vi.hoisted(() => ({
  turnActive: true,
  deletingSessionIds: [] as string[],
  sessionDeleteError: null as string | null,
  deleteSession: vi.fn(),
}));

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
    turnActive: mocks.turnActive,
    deletingSessionIds: mocks.deletingSessionIds,
    sessionDeleteError: mocks.sessionDeleteError,
    switchSession: vi.fn(),
    createSession: vi.fn(),
    renameSession: vi.fn(),
    deleteSession: mocks.deleteSession,
  }),
}));

describe("AgentSessionSidebar", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.turnActive = true;
    mocks.deletingSessionIds = [];
    mocks.sessionDeleteError = null;
  });

  afterEach(cleanup);

  it("disables deleting the active session while its ref-backed turn is active", () => {
    render(
      <AgentSessionSidebar
        collapsed={false}
        onToggleCollapse={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "会话操作" }));

    expect(screen.getByRole("menuitem", { name: "删除" })).toBeDisabled();
  });

  it("shows deletion progress and disables a duplicate request", () => {
    mocks.turnActive = false;
    mocks.deletingSessionIds = ["active-session"];
    render(
      <AgentSessionSidebar
        collapsed={false}
        onToggleCollapse={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "会话操作" }));

    expect(screen.getByRole("menuitem", { name: "删除中…" })).toBeDisabled();
  });

  it("renders a visible retry message after deletion fails", () => {
    mocks.turnActive = false;
    mocks.sessionDeleteError = "未能删除会话，请重试。";
    render(
      <AgentSessionSidebar
        collapsed={false}
        onToggleCollapse={vi.fn()}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("未能删除会话，请重试。");
  });
});
