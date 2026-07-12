import { existsSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { ROUTE_VISUALS, SITE_HEADER_BACKGROUND, getRouteVisualConfig } from "./route-visuals";

describe("route visuals", () => {
  const routeCases = [
    ["/hub", "hub", "hub-content.webp"],
    ["/hub/topic", "hub", "hub-content.webp"],
    ["/search", "search", "search-content.webp"],
    ["/search/results", "search", "search-content.webp"],
    ["/write", "write", "write-workspace.webp"],
    ["/write/draft", "write", "write-workspace.webp"],
    ["/login", "auth", "auth-arrival.webp"],
    ["/reset-password/token", "auth", "auth-arrival.webp"],
    ["/about", "about", "about-community.webp"],
    ["/about/team", "about", "about-community.webp"],
    ["/user/alice", "profile", "profile-personal.webp"],
    ["/profile/edit", "profile", "profile-personal.webp"],
    ["/settings", "settings", "settings-calm.webp"],
    ["/settings/account", "settings", "settings-calm.webp"],
    ["/archive", "archive", "archive-hall.webp"],
    ["/archive/2026", "archive", "archive-hall.webp"],
    ["/tag/typescript", "tag", "tag-trace.webp"],
    ["/post/hello-world", "post", "post-ruins.webp"],
  ] as const;

  it.each(routeCases)(
    "maps %s to %s",
    (pathname, expectedId, expectedPageFile) => {
      const config = getRouteVisualConfig(pathname);

      expect(config?.id).toBe(expectedId);
      expect(config?.page.image.split("/").at(-1)).toBe(expectedPageFile);
    },
  );

  it("keeps one shared white-background image in every ordinary site header", () => {
    expect(SITE_HEADER_BACKGROUND.image).toBe("/images/site/backgrounds/hub-hero.webp");
    expect(SITE_HEADER_BACKGROUND.positionDesktop).toBe("52% 0%");
    expect(SITE_HEADER_BACKGROUND.positionMobile).toBe("72% 0%");
  });

  it("keeps the writing subject visible near the top of its page background", () => {
    const write = ROUTE_VISUALS.find(({ id }) => id === "write");

    expect(write?.page.positionDesktop).toBe("50% 4%");
    expect(write?.page.positionMobile).toBe("52% 0%");
    expect(write?.page.overlayAlpha).toBe(0.16);
  });

  it.each(["/", "/agent", "/agent/history", "/chtholly", "/admin", "/admin/posts"])(
    "does not decorate excluded route %s",
    (pathname) => {
      expect(getRouteVisualConfig(pathname)).toBeNull();
    },
  );

  it.each(["/searching", "/writing", "/administrator"])(
    "does not match colliding prefix %s",
    (pathname) => {
      expect(getRouteVisualConfig(pathname)).toBeNull();
    },
  );

  it("assigns one formal page image to each visual id", () => {
    const pageImages = ROUTE_VISUALS.map(({ page }) => page.image);

    const ids = new Set(ROUTE_VISUALS.map(({ id }) => id));

    expect(ids.size).toBe(ROUTE_VISUALS.length);
    expect(new Set(pageImages).size).toBe(ROUTE_VISUALS.length);
    for (const image of pageImages) {
      expect(image).toMatch(/^\/images\/site\/backgrounds\//);
      expect(image).not.toContain("_incoming");
    }
  });

  it("deeply freezes the exported visual configuration graph", () => {
    expect(Object.isFrozen(ROUTE_VISUALS)).toBe(true);

    for (const config of ROUTE_VISUALS) {
      expect(Object.isFrozen(config)).toBe(true);
      expect(Object.isFrozen(config.page)).toBe(true);
    }
    expect(Object.isFrozen(SITE_HEADER_BACKGROUND)).toBe(true);
  });

  it.each([
    "/hub",
    "/search",
    "/write",
    "/login",
    "/reset-password",
    "/about",
    "/user/example",
    "/profile/edit",
    "/settings",
    "/archive",
    "/tag/example",
    "/post/example",
  ])("decorates current representative route %s", (pathname) => {
    expect(getRouteVisualConfig(pathname)).not.toBeNull();
  });

  it.each([
    ["/hub/", "hub"],
    ["/write/", "write"],
  ])("matches trailing slash route %s", (pathname, expectedId) => {
    expect(getRouteVisualConfig(pathname)?.id).toBe(expectedId);
  });

  it("references public files that exist", () => {
    const images = new Set(
      [SITE_HEADER_BACKGROUND.image, ...ROUTE_VISUALS.map(({ page }) => page.image)],
    );

    for (const image of images) {
      const publicPath = path.resolve(process.cwd(), "public", image.slice(1));
      expect(existsSync(publicPath), `${image} should exist under public`).toBe(true);
    }
  });
});
