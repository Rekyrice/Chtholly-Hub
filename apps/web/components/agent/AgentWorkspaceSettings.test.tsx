import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AgentWorkspaceSettings from "@/components/agent/AgentWorkspaceSettings";

const mocks = vi.hoisted(() => ({
  clearingConversation: false,
  conversationClearError: null as string | null,
  clearConversation: vi.fn(async () => true),
}));

vi.mock("@/components/agent/AgentChatProvider", () => ({
  useAgentChatContext: () => ({
    showSteps: false,
    setShowSteps: vi.fn(),
    workspaceDark: false,
    setWorkspaceDark: vi.fn(),
    richMarkdown: true,
    setRichMarkdown: vi.fn(),
    messages: [{ id: "message", role: "assistant", content: "保留" }],
    busy: false,
    turnActive: false,
    clearingConversation: mocks.clearingConversation,
    conversationClearError: mocks.conversationClearError,
    clearConversation: mocks.clearConversation,
  }),
}));

describe("AgentWorkspaceSettings", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.clearingConversation = false;
    mocks.conversationClearError = null;
    mocks.clearConversation.mockReset().mockResolvedValue(true);
  });

  afterEach(cleanup);

  it("shows clearing progress and prevents a duplicate clear", () => {
    mocks.clearingConversation = true;
    render(<AgentWorkspaceSettings />);

    fireEvent.click(screen.getByRole("button", { name: "工作台设置" }));

    expect(screen.getByRole("button", { name: "清理中…" })).toBeDisabled();
  });

  it("keeps the panel open and displays retry feedback after a clear failure", () => {
    mocks.conversationClearError = "未能清空当前对话，请重试。";
    render(<AgentWorkspaceSettings />);

    fireEvent.click(screen.getByRole("button", { name: "工作台设置" }));

    expect(screen.getByRole("alert")).toHaveTextContent("未能清空当前对话，请重试。");
    expect(screen.getByRole("dialog", { name: "工作台设置" })).toBeInTheDocument();
  });
});
