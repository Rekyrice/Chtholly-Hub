import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import AgentWorkspace from "@/components/agent/AgentWorkspace";

const mocks = vi.hoisted(() => ({
  replace: vi.fn(),
  activateContextSession: vi.fn(() => "article-session"),
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mocks.replace }),
  useSearchParams: () => new URLSearchParams(
    "taskType=page-explain&context=post%3Adungeon-meshi"
      + "&postTitle=%E5%90%83%E6%8E%89%E7%BA%A2%E9%BE%99%E8%BF%99%E4%BB%B6%E4%BA%8B"
      + "&postId=42",
  ),
}));

vi.mock("@/components/agent/AgentChatProvider", () => ({
  useAgentChatContext: () => ({
    loggedIn: true,
    activeSessionId: "article-session",
    activeSessionContext: {
      contextKey: "post:dungeon-meshi",
      contextTitle: "吃掉红龙这件事",
      postId: "42",
    },
    sessions: [{ id: "ordinary", title: "普通会话", messages: [] }],
    switchSession: vi.fn(),
    activateContextSession: mocks.activateContextSession,
    workspaceDark: false,
    proactiveNotifications: [],
  }),
}));

vi.mock("@/components/agent/AgentChatPanel", () => ({
  default: () => <div data-testid="chat-panel" />,
}));
vi.mock("@/components/agent/AgentLive2DStage", () => ({
  default: () => <div data-testid="live2d-stage" />,
}));
vi.mock("@/components/agent/AgentSessionSidebar", () => ({
  default: () => <div data-testid="session-sidebar" />,
}));
vi.mock("@/components/agent/AgentSkillModeSelector", () => ({
  default: () => <div data-testid="skill-selector" />,
  getAgentSkillMode: () => null,
  parseAgentTaskType: (value: string | null) => value,
}));
vi.mock("@/lib/hooks/useMinWidth", () => ({
  useMinWidth: () => true,
}));
vi.mock("@/lib/services/agentService", () => ({
  agentService: { recentExperiences: vi.fn(async () => []) },
}));

afterEach(() => {
  cleanup();
  mocks.replace.mockReset();
  mocks.activateContextSession.mockClear();
});

describe("AgentWorkspace article companion", () => {
  it("activates the article session, pins it in the URL, and shows the context banner", async () => {
    render(<AgentWorkspace />);

    expect(screen.getByText("正在陪读《吃掉红龙这件事》")).toBeInTheDocument();
    await waitFor(() => {
      expect(mocks.activateContextSession).toHaveBeenCalledWith({
        contextKey: "post:dungeon-meshi",
        contextTitle: "吃掉红龙这件事",
        postId: "42",
      });
    });
    expect(mocks.replace).toHaveBeenCalledWith(
      expect.stringContaining("session=article-session"),
      { scroll: false },
    );
  });
});
