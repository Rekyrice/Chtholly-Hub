/** Agent 对话阶段，用于驱动 Live2D 表情与动作 */
export type AgentLivePhase = "idle" | "think" | "act" | "speaking" | "done" | "error";

export type Live2DHandle = {
  setExpression: (name: string) => void;
  startMotion: (group: string, index?: number) => void;
  /** 随机播放一条点击语音（含动作、表情、台词回调） */
  playTapLine: (index?: number) => void;
  setSpeaking: (speaking: boolean) => void;
  /** 设置 Live2D 模型参数（如 PARAM_CHEEK） */
  setParam: (id: string, value: number) => void;
};
