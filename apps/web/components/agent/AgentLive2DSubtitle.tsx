"use client";

type AgentLive2DSubtitleProps = {
  lines: string[];
  visible: boolean;
};

/** Live2D 角色上方日语台词浮层 */
export default function AgentLive2DSubtitle({ lines, visible }: AgentLive2DSubtitleProps) {
  if (!visible || lines.length === 0) return null;

  return (
    <div className="agent-live2d-subtitle" role="status" aria-live="polite">
      <div className="agent-live2d-subtitle-inner">
        {lines.map((line, index) => (
          <p key={`${index}-${line}`} className="agent-live2d-subtitle-line">
            {line}
          </p>
        ))}
      </div>
    </div>
  );
}
