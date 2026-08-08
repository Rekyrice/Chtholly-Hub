import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import AgentSkillModeSelector from "@/components/agent/AgentSkillModeSelector";

afterEach(cleanup);

describe("AgentSkillModeSelector", () => {
  it("exposes three native task buttons and marks the selected mode", () => {
    const onChange = vi.fn();
    render(<AgentSkillModeSelector value="evidence-outline" onChange={onChange} />);

    expect(screen.getByRole("group", { name: "Agent 任务模式" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "页面解释" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "资料大纲" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "草稿事实核查" })).toHaveAttribute("aria-pressed", "false");

    fireEvent.click(screen.getByRole("button", { name: "草稿事实核查" }));
    expect(onChange).toHaveBeenCalledWith("draft-fact-check");
  });

  it("allows cancelling the active mode back to ordinary chat", () => {
    const onChange = vi.fn();
    render(<AgentSkillModeSelector value="page-explain" onChange={onChange} />);

    fireEvent.click(screen.getByRole("button", { name: "退出任务模式" }));
    expect(onChange).toHaveBeenCalledWith(null);
  });

  it("cancels the active mode when its task button is clicked again", () => {
    const onChange = vi.fn();
    render(<AgentSkillModeSelector value="evidence-outline" onChange={onChange} />);

    fireEvent.click(screen.getByRole("button", { name: "资料大纲" }));
    expect(onChange).toHaveBeenCalledWith(null);
  });
});
