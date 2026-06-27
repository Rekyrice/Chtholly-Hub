export type AgentEventType = "think" | "act" | "observe" | "delta" | "final" | "error" | "cleared";

export interface AgentWsEnvelope {
  type: AgentEventType;
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
}
