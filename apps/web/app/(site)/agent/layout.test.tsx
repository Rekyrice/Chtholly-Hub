import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import AgentLayout from "@/app/(site)/agent/layout";

vi.mock("@/components/agent/AgentChatProvider", () => ({
  AgentChatProvider: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="agent-workspace-provider">{children}</div>
  ),
}));
vi.mock("@/components/ProactiveNotification", () => ({
  ProactiveNotification: () => <div data-testid="agent-workspace-proactive" />,
}));

describe("AgentLayout", () => {
  it("owns one immediate provider for the Agent workspace", () => {
    render(<AgentLayout><div>workspace</div></AgentLayout>);

    const provider = screen.getByTestId("agent-workspace-provider");
    expect(provider).toHaveTextContent("workspace");
    expect(screen.getByTestId("agent-workspace-proactive")).toBeInTheDocument();
  });
});
