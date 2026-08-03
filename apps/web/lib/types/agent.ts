export type AgentEventType =
  | "accepted"
  | "rejected"
  | "think"
  | "act"
  | "observe"
  | "delta"
  | "final"
  | "error"
  | "cleared"
  | "proactive";

export type AgentTaskType =
  | "page-explain"
  | "evidence-outline"
  | "draft-fact-check";

export type AgentSendOptions = {
  taskType?: AgentTaskType;
};

export interface AgentWsEnvelope {
  type: AgentEventType;
  requestId?: string;
  turnId?: string;
  data: Record<string, unknown>;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "system";
  content: string;
  /** 推理过程（折叠展示） */
  steps?: string[];
  /** 是否正在流式输出 */
  streaming?: boolean;
  /** 助手回答的终态；旧消息未携带该字段时按正常完成兼容。 */
  completionState?: "done" | "interrupted";
}

export type ProactiveNotificationItem = {
  instanceId: string;
  type: "missing-you" | "new-posts" | "thought" | string;
  message: string;
  timestamp: string;
  channel?: "FLOATING" | "AGENT_PAGE" | string;
};

export type AgentExperience = {
  text: string;
  valueScore: number;
  importance: number;
  createdAt: string;
  source: string;
};

export type AgentWeeklyExperience = {
  weekKey: string;
  summary: string;
};

export type AgentArchivedExperience = {
  id: number;
  text: string;
  importance: number;
  source: string;
  createdAt: string;
  archivedAt: string;
};

export type AgentExperienceTimeline = {
  recent: AgentExperience[];
  weeklySummaries: AgentWeeklyExperience[];
  archived: AgentArchivedExperience[];
};
