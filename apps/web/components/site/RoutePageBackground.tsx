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
  const requestedUrl = background.images[requestedIndex] ?? "";
  const requestedLayer: ImageLayer = {
    index: requestedIndex,
    url: requestedUrl,
  };
  const [reduceMotion, setReduceMotion] = useState(false);
  const [visible, setVisible] = useState({
    current: requestedLayer,
    previous: null as ImageLayer | null,
    entered: true,
  });
  const displayedUrl = visible.current.url;

  useEffect(() => {
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    const sync = () => setReduceMotion(query.matches);
    sync();
    query.addEventListener("change", sync);
    return () => query.removeEventListener("change", sync);
  }, []);

  useEffect(() => {
    if (displayedUrl === requestedUrl) return;

    const nextLayer: ImageLayer = {
      index: requestedIndex,
      url: requestedUrl,
    };

    if (reduceMotion || background.images.length < 2) {
      const frame = window.requestAnimationFrame(() => {
        setVisible({ current: nextLayer, previous: null, entered: true });
      });
      return () => window.cancelAnimationFrame(frame);
    }

    let cancelled = false;
    const image = new Image();
    const reveal = () => {
      if (cancelled) return;
      setVisible((current) => current.current.url === nextLayer.url
        ? current
        : {
            current: nextLayer,
            previous: current.current,
            entered: false,
          });
    };

    image.src = nextLayer.url;
    if (typeof image.decode === "function") {
      void image.decode().then(reveal, reveal);
    } else {
      image.onload = reveal;
      image.onerror = reveal;
    }

    return () => {
      cancelled = true;
      image.onload = null;
      image.onerror = null;
    };
  }, [background.images.length, displayedUrl, reduceMotion, requestedIndex, requestedUrl]);

  useEffect(() => {
    if (background.images.length < 2) return;

    const nextIndex = normalizeIndex(requestedIndex + 1, background.images.length);
    const nextUrl = background.images[nextIndex];
    if (!nextUrl) return;

    const image = new Image();
    image.src = nextUrl;
  }, [background.images, requestedIndex]);

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
