import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import SiteLayout from "@/app/(site)/layout";

vi.mock("@/components/site/SiteChrome", () => ({
  default: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="site-chrome">{children}</div>
  ),
}));
vi.mock("@/components/agent/DeferredAgentRuntime", () => ({
  default: () => <div data-testid="deferred-agent-runtime" />,
}));
vi.mock("@/components/agent/AgentChatProvider", () => ({
  AgentChatProvider: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="global-agent-provider">{children}</div>
  ),
}));
vi.mock("@/components/ProactiveNotification", () => ({
  ProactiveNotification: () => <div data-testid="global-proactive-notification" />,
}));

describe("SiteLayout", () => {
  it("keeps ordinary children outside the global Agent provider", () => {
    render(<SiteLayout><div>page-content</div></SiteLayout>);

    expect(screen.getByTestId("site-chrome")).toHaveTextContent("page-content");
    expect(screen.getByTestId("deferred-agent-runtime")).toBeInTheDocument();
    expect(screen.queryByTestId("global-agent-provider")).not.toBeInTheDocument();
    expect(screen.queryByTestId("global-proactive-notification")).not.toBeInTheDocument();
  });
});
