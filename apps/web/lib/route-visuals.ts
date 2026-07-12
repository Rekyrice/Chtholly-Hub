export type PageVisualBackground = {
  readonly images: readonly string[];
  readonly positionDesktop: string;
  readonly positionMobile: string;
  readonly overlayAlpha: number;
  readonly blurPx: number;
  readonly saturate: number;
};

export type RouteVisualConfig = {
  readonly id: string;
  readonly page: PageVisualBackground;
};

const BACKGROUND_ROOT = "/images/site/backgrounds/";
const DEFAULT_POSITION = "50% 50%";
const DEFAULT_OVERLAY_ALPHA = 0.2;
const DEFAULT_BLUR_PX = 1;
const DEFAULT_SATURATE = 0.98;

function background(
  imageFiles: readonly string[],
  positionDesktop = DEFAULT_POSITION,
  positionMobile = DEFAULT_POSITION,
  overlayAlpha = DEFAULT_OVERLAY_ALPHA,
  blurPx = DEFAULT_BLUR_PX,
  saturate = DEFAULT_SATURATE,
): PageVisualBackground {
  const images = Object.freeze(imageFiles.map((image) => `${BACKGROUND_ROOT}${image}`));

  return Object.freeze({
    images,
    positionDesktop,
    positionMobile,
    overlayAlpha,
    blurPx,
    saturate,
  });
}

function routeVisual(
  id: string,
  imageFiles: readonly string[],
  positionDesktop = DEFAULT_POSITION,
  positionMobile = DEFAULT_POSITION,
): RouteVisualConfig {
  return Object.freeze({ id, page: background(imageFiles, positionDesktop, positionMobile) });
}

export const ROUTE_VISUALS: readonly RouteVisualConfig[] = Object.freeze([
  routeVisual("hub", ["hub-01.webp", "hub-02.webp", "hub-03.webp"]),
  routeVisual("search", ["search.webp"]),
  routeVisual("write", ["write.webp"]),
  routeVisual("login", ["login.webp"]),
  routeVisual("reset-password", ["reset-password.webp"]),
  routeVisual("about", ["about.webp"]),
  routeVisual("user", ["user.webp"], "55% 22%", "56% 28%"),
  routeVisual("profile", ["search.webp"]),
  routeVisual("settings", ["settings.webp"]),
  routeVisual("archive", ["archive.webp"]),
  routeVisual("tag", ["tag.webp"]),
  routeVisual("post", ["post.webp"]),
  routeVisual("admin", ["admin.webp"]),
]);

export const NOT_FOUND_VISUAL = routeVisual("not-found", ["not-found.webp"]);

const ROUTE_SEGMENTS: ReadonlyArray<readonly [segment: string, visualId: string]> = [
  ["/hub", "hub"],
  ["/search", "search"],
  ["/write", "write"],
  ["/login", "login"],
  ["/reset-password", "reset-password"],
  ["/about", "about"],
  ["/user", "user"],
  ["/profile", "profile"],
  ["/settings", "settings"],
  ["/archive", "archive"],
  ["/tag", "tag"],
  ["/post", "post"],
  ["/admin", "admin"],
];

function matchesSegment(pathname: string, segment: string): boolean {
  return pathname === segment || pathname.startsWith(`${segment}/`);
}

export function getRouteVisualConfig(pathname: string): RouteVisualConfig | null {
  const match = ROUTE_SEGMENTS.find(([segment]) => matchesSegment(pathname, segment));
  if (!match) {
    return null;
  }

  return ROUTE_VISUALS.find(({ id }) => id === match[1]) ?? null;
}
