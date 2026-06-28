"use client";

/** Live2D 展示区占位（L1-L2 接入前） */
export default function AgentLive2DStage() {
  return (
    <div className="agent-live2d-stage" data-testid="agent-live2d-stage">
      <div className="agent-live2d-stage-inner">
        <span className="agent-avatar-lg" aria-hidden="true">
          C
        </span>
        <p className="mt-4 text-lg font-medium text-on-primary">珂朵莉</p>
        <p className="mt-2 text-sm text-on-primary/80 max-w-xs text-center leading-relaxed">
          Live2D 角色展示区
          <br />
          <span className="text-xs opacity-75">（L1-L2 阶段接入）</span>
        </p>
      </div>
    </div>
  );
}
