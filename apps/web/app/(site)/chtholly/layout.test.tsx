import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import ChthollyLayout from "@/app/(site)/chtholly/layout";

vi.mock("@/components/agent/AgentChatProvider", () => ({
  AgentChatProvider: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="chtholly-agent-provider">{children}</div>
  ),
}));

describe("ChthollyLayout", () => {
  it("owns one immediate provider without another floating runtime", () => {
    render(<ChthollyLayout><div>room</div></ChthollyLayout>);

    expect(screen.getAllByTestId("chtholly-agent-provider")).toHaveLength(1);
    expect(screen.getByTestId("chtholly-agent-provider")).toHaveTextContent("room");
  });
});
