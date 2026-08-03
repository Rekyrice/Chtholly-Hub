import { apiFetch } from "./apiClient";
import type { AgentExperience, AgentExperienceTimeline } from "@/lib/types/agent";

const AGENT_PREFIX = "/api/v1/agent";

export const agentService = {
  clearSessionMemory: (sessionId: string) =>
    apiFetch<void>(
      `${AGENT_PREFIX}/sessions/${encodeURIComponent(sessionId)}/memory`,
      { method: "DELETE" },
    ),

  recentExperiences: (limit = 5) => {
    const params = new URLSearchParams({ limit: String(limit) });
    return apiFetch<AgentExperience[]>(`${AGENT_PREFIX}/experiences?${params.toString()}`);
  },

  experienceTimeline: () =>
    apiFetch<AgentExperienceTimeline>(`${AGENT_PREFIX}/experiences/timeline`),
};
