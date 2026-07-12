import type { CSSProperties } from "react";
import type { VisualBackground } from "@/lib/route-visuals";

type RouteBackgroundStyle = CSSProperties & {
  "--route-bg-image": string;
  "--route-bg-position": string;
  "--route-bg-position-mobile": string;
  "--route-bg-overlay": string;
  "--route-bg-blur": string;
  "--route-bg-saturate": string;
};

export default function RoutePageBackground({ background }: { background: VisualBackground }) {
  const style: RouteBackgroundStyle = {
    "--route-bg-image": `url("${background.image}")`,
    "--route-bg-position": background.positionDesktop,
    "--route-bg-position-mobile": background.positionMobile,
    "--route-bg-overlay": String(background.overlayAlpha),
    "--route-bg-blur": `${background.blurPx}px`,
    "--route-bg-saturate": String(background.saturate),
  };

  return (
    <div aria-hidden="true" className="route-page-background" data-testid="route-page-background" style={style}>
      <div className="route-page-background__image" />
      <div className="route-page-background__overlay" />
    </div>
  );
}
