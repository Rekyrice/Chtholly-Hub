/** Agent 工作台全页背景：插画 + 半透明蒙版，UI 叠在上方 */
export default function AgentPageBackground() {
  return (
    <div className="agent-page-background" aria-hidden="true">
      <div className="agent-page-background__image" />
      <div className="agent-page-background__scrim" />
    </div>
  );
}
