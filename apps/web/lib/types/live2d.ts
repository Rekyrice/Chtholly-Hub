/** Agent 对话阶段，用于驱动 Live2D 表情与动作 */
export type AgentLivePhase = "idle" | "think" | "act" | "speaking" | "done" | "error";

export type Live2DHandle = {
  setExpression: (name: string) => void;
  startMotion: (group: string, index?: number) => void;
  setSpeaking: (speaking: boolean) => void;
};
