import { describe, expect, it } from "vitest";
import { getAgentRuntimePolicy } from "@/components/agent/agentRuntimePolicy";

describe("getAgentRuntimePolicy", () => {
  it.each([
    ["/", { landing: true, agentWorkspace: false, writeWorkspace: false, proactive: true, floating: false }],
    ["/hub", { landing: false, agentWorkspace: false, writeWorkspace: false, proactive: true, floating: true }],
    ["/agent", { landing: false, agentWorkspace: true, writeWorkspace: false, proactive: false, floating: false }],
    ["/agent/history", { landing: false, agentWorkspace: true, writeWorkspace: false, proactive: false, floating: false }],
    ["/agentic", { landing: false, agentWorkspace: false, writeWorkspace: false, proactive: true, floating: true }],
    ["/write", { landing: false, agentWorkspace: false, writeWorkspace: true, proactive: true, floating: false }],
    ["/write/draft", { landing: false, agentWorkspace: false, writeWorkspace: true, proactive: true, floating: false }],
  ])("maps %s without prefix collisions", (pathname, expected) => {
    expect(getAgentRuntimePolicy(pathname)).toEqual(expected);
  });
});
