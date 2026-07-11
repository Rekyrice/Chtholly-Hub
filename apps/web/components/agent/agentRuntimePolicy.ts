export type AgentRuntimePolicy = {
  landing: boolean;
  agentWorkspace: boolean;
  writeWorkspace: boolean;
  proactive: boolean;
  floating: boolean;
};

function isRouteSegment(pathname: string, segment: string): boolean {
  return pathname === segment || pathname.startsWith(`${segment}/`);
}

export function getAgentRuntimePolicy(pathname: string): AgentRuntimePolicy {
  const landing = pathname === "/";
  const agentWorkspace = isRouteSegment(pathname, "/agent");
  const writeWorkspace = isRouteSegment(pathname, "/write");

  return {
    landing,
    agentWorkspace,
    writeWorkspace,
    proactive: !agentWorkspace,
    floating: !landing && !agentWorkspace && !writeWorkspace,
  };
}
