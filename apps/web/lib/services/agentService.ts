import { apiFetch } from "./apiClient";
import type { AgentExperience } from "@/lib/types/agent";

const AGENT_PREFIX = "/api/v1/agent";

export const agentService = {
  recentExperiences: (limit = 5) => {
    const params = new URLSearchParams({ limit: String(limit) });
    return apiFetch<AgentExperience[]>(`${AGENT_PREFIX}/experiences?${params.toString()}`);
  },
};
