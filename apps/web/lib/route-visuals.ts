export type VisualBackground = {
  image: string;
  positionDesktop: string;
  positionMobile: string;
  overlayAlpha: number;
  blurPx: number;
  saturate: number;
};

export type RouteVisualConfig = {
  id: string;
  hero?: VisualBackground;
  page: VisualBackground;
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
  return {
    image: `${BACKGROUND_ROOT}${image}`,
    positionDesktop,
    positionMobile,
    overlayAlpha,
    blurPx,
    saturate,
  };
}

const searchBackground = background("search-content.webp", "52% 40%", "56% 40%", 0.2, 0.8);
const authBackground = background("auth-arrival.webp", "34% 55%", "50% 48%", 0.2, 1);
const aboutBackground = background("about-community.webp", "50% 34%", "50% 30%", 0.2, 1);
const profileBackground = background("profile-personal.webp", "42% 40%", "48% 38%", 0.2, 1);
const settingsBackground = background("settings-calm.webp", "50% 42%", "54% 42%", 0.18, 1);
const archiveBackground = background("archive-hall.webp", "50% 38%", "58% 38%", 0.2, 1);
const tagBackground = background("tag-trace.webp", "62% 42%", "70% 42%", 0.2, 1);
const postBackground = background("post-ruins.webp", "52% 38%", "50% 40%", 0.2, 1);

export const ROUTE_VISUALS: readonly RouteVisualConfig[] = [
  {
    id: "hub",
    hero: background("hub-hero.webp", "52% 45%", "58% 44%", 0.18, 0),
    page: background("hub-content.webp", "50% 45%", "52% 42%", 0.18, 1, 0.96),
  },
  { id: "search", hero: searchBackground, page: searchBackground },
  {
    id: "write",
    page: background("write-workspace.webp", "66% 38%", "68% 36%", 0.22, 0.5),
  },
  { id: "auth", hero: authBackground, page: authBackground },
  { id: "about", hero: aboutBackground, page: aboutBackground },
  { id: "profile", hero: profileBackground, page: profileBackground },
  { id: "settings", hero: settingsBackground, page: settingsBackground },
  { id: "archive", hero: archiveBackground, page: archiveBackground },
  { id: "tag", hero: tagBackground, page: tagBackground },
  { id: "post", hero: postBackground, page: postBackground },
] as const;

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
