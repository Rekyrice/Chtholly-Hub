import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const avatarConsumers = [
  "components/site/Navbar.tsx",
  "components/site/Sidebar.tsx",
  "components/agent/AgentChatPanel.tsx",
  "components/agent/AgentMessageList.tsx",
] as const;

describe("Chtholly brand avatar contract", () => {
  it.each(avatarConsumers)("uses the shared transparent avatar in %s", (file) => {
    const source = readFileSync(file, "utf8");

    expect(source).toContain("ChthollyAvatar");
    expect(source).not.toMatch(/className="(?:navbar-brand-icon|agent-avatar-(?:sm|md))[^\"]*"[^>]*>\s*C\s*</s);
  });
});
