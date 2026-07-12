export type VisualBackground = {
  readonly image: string;
  readonly positionDesktop: string;
  readonly positionMobile: string;
  readonly overlayAlpha: number;
  readonly blurPx: number;
  readonly saturate: number;
};

export type RouteVisualConfig = {
  readonly id: string;
  readonly page: VisualBackground;
};

const BACKGROUND_ROOT = "/images/site/backgrounds/";
const DEFAULT_SATURATE = 0.98;

function background(
  image: string,
  positionDesktop: string,
  positionMobile: string,
  overlayAlpha: number,
  blurPx: number,
  saturate = DEFAULT_SATURATE,
): VisualBackground {
  return Object.freeze({
    image: `${BACKGROUND_ROOT}${image}`,
    positionDesktop,
    positionMobile,
    overlayAlpha,
    blurPx,
    saturate,
  });
}

function routeVisual(config: RouteVisualConfig): RouteVisualConfig {
  return Object.freeze(config);
}

export const SITE_HEADER_BACKGROUND = background("hub-hero.webp", "52% 0%", "72% 0%", 0.18, 0);

const searchBackground = background("search-content.webp", "52% 40%", "56% 40%", 0.2, 0.8);
const authBackground = background("auth-arrival.webp", "34% 55%", "50% 48%", 0.2, 1);
const aboutBackground = background("about-community.webp", "50% 34%", "50% 30%", 0.2, 1);
const profileBackground = background("profile-personal.webp", "42% 40%", "48% 38%", 0.2, 1);
const settingsBackground = background("settings-calm.webp", "50% 42%", "54% 42%", 0.18, 1);
const archiveBackground = background("archive-hall.webp", "50% 38%", "58% 38%", 0.2, 1);
const tagBackground = background("tag-trace.webp", "62% 42%", "70% 42%", 0.2, 1);
const postBackground = background("post-ruins.webp", "52% 38%", "50% 40%", 0.2, 1);

export const ROUTE_VISUALS: readonly RouteVisualConfig[] = Object.freeze([
  routeVisual({
    id: "hub",
    page: background("hub-content.webp", "50% 45%", "52% 42%", 0.18, 1, 0.96),
  }),
  routeVisual({ id: "search", page: searchBackground }),
  routeVisual({
    id: "write",
    page: background("write-workspace.webp", "50% 4%", "52% 0%", 0.16, 0.5),
  }),
  routeVisual({ id: "auth", page: authBackground }),
  routeVisual({ id: "about", page: aboutBackground }),
  routeVisual({ id: "profile", page: profileBackground }),
  routeVisual({ id: "settings", page: settingsBackground }),
  routeVisual({ id: "archive", page: archiveBackground }),
  routeVisual({ id: "tag", page: tagBackground }),
  routeVisual({ id: "post", page: postBackground }),
]);

const ROUTE_SEGMENTS: ReadonlyArray<readonly [segment: string, visualId: string]> = [
  ["/hub", "hub"],
  ["/search", "search"],
  ["/write", "write"],
  ["/login", "auth"],
  ["/reset-password", "auth"],
  ["/about", "about"],
  ["/user", "profile"],
  ["/profile", "profile"],
  ["/settings", "settings"],
  ["/archive", "archive"],
  ["/tag", "tag"],
  ["/post", "post"],
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
