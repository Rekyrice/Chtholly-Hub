"use client";

import { useEffect, useState } from "react";
import type { CSSProperties, TransitionEvent } from "react";
import type { PageVisualBackground } from "@/lib/route-visuals";

type RouteBackgroundStyle = CSSProperties & {
  "--route-bg-position": string;
  "--route-bg-position-mobile": string;
  "--route-bg-overlay": string;
  "--route-bg-blur": string;
  "--route-bg-saturate": string;
};

type ImageStyle = CSSProperties & { "--route-bg-image": string };

type RoutePageBackgroundProps = {
  background: PageVisualBackground;
  activeIndex?: number;
};

type ImageLayer = {
  index: number;
  url: string;
};

function normalizeIndex(index: number, length: number) {
  return length === 0 ? 0 : ((index % length) + length) % length;
}

export default function RoutePageBackground({
  background,
  activeIndex = 0,
}: RoutePageBackgroundProps) {
  const requestedIndex = normalizeIndex(activeIndex, background.images.length);
  const requestedLayer: ImageLayer = {
    index: requestedIndex,
    url: background.images[requestedIndex] ?? "",
  };
  const [reduceMotion, setReduceMotion] = useState(false);
  const [visible, setVisible] = useState({
    current: requestedLayer,
    previous: null as ImageLayer | null,
    requestedUrl: requestedLayer.url,
    reduced: false,
    entered: true,
  });

  useEffect(() => {
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const sync = () => setReduceMotion(query.matches);
    sync();
    query.addEventListener("change", sync);
    return () => query.removeEventListener("change", sync);
  }, []);

  if (visible.requestedUrl !== requestedLayer.url || visible.reduced !== reduceMotion) {
    const sameImage = visible.current.url === requestedLayer.url;
    setVisible({
      current: requestedLayer,
      previous:
        reduceMotion || sameImage ? null : visible.current,
      requestedUrl: requestedLayer.url,
      reduced: reduceMotion,
      entered: reduceMotion || sameImage,
    });
  }

  const currentUrl = visible.current.url;

  useEffect(() => {
    if (visible.entered) return;

    const frame = window.requestAnimationFrame(() => {
      setVisible((current) => current.current.url === currentUrl
        ? { ...current, entered: true }
        : current);
    });
    return () => window.cancelAnimationFrame(frame);
  }, [currentUrl, visible.entered]);

  const style: RouteBackgroundStyle = {
    "--route-bg-position": background.positionDesktop,
    "--route-bg-position-mobile": background.positionMobile,
    "--route-bg-overlay": String(background.overlayAlpha),
    "--route-bg-blur": `${background.blurPx}px`,
    "--route-bg-saturate": String(background.saturate),
  };
  const layers = visible.previous === null
    ? [visible.current]
    : [visible.previous, visible.current];

  const finishTransition = (url: string, event: TransitionEvent<HTMLDivElement>) => {
    if (event.propertyName === "opacity") {
      setVisible((current) =>
        current.entered && current.previous?.url === url
          ? { ...current, previous: null }
          : current,
      );
    }
  };

  return (
    <div aria-hidden="true" className="route-page-background" data-testid="route-page-background" style={style}>
      {layers.map((layer) => {
        const active = layer.url === visible.current.url;
        const previousVisible = !active && !visible.entered;
        const imageStyle: ImageStyle = {
          "--route-bg-image": `url("${layer.url}")`,
        };
        return (
          <div
            className={`route-page-background__image${active && visible.entered ? " route-page-background__image--active" : ""}${previousVisible ? " route-page-background__image--previous-visible" : ""}`}
            data-testid="route-page-background-image"
            data-image-index={layer.index}
            key={layer.url}
            onTransitionEnd={!active ? (event) => finishTransition(layer.url, event) : undefined}
            style={imageStyle}
          />
        );
      })}
      <div className="route-page-background__overlay" data-testid="route-page-background-overlay" />
    </div>
  );
}
