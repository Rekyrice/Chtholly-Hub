import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AuthenticatedAgentRuntime, {
  getAgentRuntimeVisibility,
} from "@/components/agent/AuthenticatedAgentRuntime";

const navigation = vi.hoisted(() => ({ pathname: "/hub" }));

vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
}));

vi.mock("@/components/agent/AgentChatProvider", () => ({
  AgentChatProvider: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="agent-chat-provider">{children}</div>
  ),
}));
vi.mock("@/components/ProactiveNotification", () => ({
  ProactiveNotification: () => <div data-testid="proactive-notification" />,
}));
vi.mock("@/components/agent/FloatingAgent", () => ({
  default: () => <div data-testid="floating-agent" />,
}));

describe("getAgentRuntimeVisibility", () => {
  it.each([
    ["/hub", { proactive: true, floating: true }],
    ["/write", { proactive: true, floating: false }],
    ["/", { proactive: true, floating: false }],
    ["/agent", { proactive: false, floating: false }],
    ["/agent/history", { proactive: false, floating: false }],
  ])("maps %s to the expected runtime surfaces", (pathname, expected) => {
    expect(getAgentRuntimeVisibility(pathname)).toEqual(expected);
  });
});

describe("AuthenticatedAgentRuntime", () => {
  beforeEach(() => {
    navigation.pathname = "/hub";
  });

  afterEach(() => {
    cleanup();
  });

  it("mounts notifications and the floating Agent on ordinary pages", () => {
    render(<AuthenticatedAgentRuntime />);

    expect(screen.getByTestId("agent-chat-provider")).toBeInTheDocument();
    expect(screen.getByTestId("proactive-notification")).toBeInTheDocument();
    expect(screen.getByTestId("floating-agent")).toBeInTheDocument();
  });

  it.each(["/write", "/"])("keeps notifications without a floating Agent on %s", (pathname) => {
    navigation.pathname = pathname;

    render(<AuthenticatedAgentRuntime />);

    expect(screen.getByTestId("proactive-notification")).toBeInTheDocument();
    expect(screen.queryByTestId("floating-agent")).not.toBeInTheDocument();
  });
});
