import { existsSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { ROUTE_VISUALS, getRouteVisualConfig } from "./route-visuals";

describe("route visuals", () => {
  const routeCases = [
    ["/hub", "hub", "hub-hero.webp", "hub-content.webp"],
    ["/hub/topic", "hub", "hub-hero.webp", "hub-content.webp"],
    ["/search", "search", "search-content.webp", "search-content.webp"],
    ["/search/results", "search", "search-content.webp", "search-content.webp"],
    ["/write", "write", null, "write-workspace.webp"],
    ["/write/draft", "write", null, "write-workspace.webp"],
    ["/login", "auth", "auth-arrival.webp", "auth-arrival.webp"],
    ["/reset-password/token", "auth", "auth-arrival.webp", "auth-arrival.webp"],
    ["/about", "about", "about-community.webp", "about-community.webp"],
    ["/about/team", "about", "about-community.webp", "about-community.webp"],
    ["/user/alice", "profile", "profile-personal.webp", "profile-personal.webp"],
    ["/profile/edit", "profile", "profile-personal.webp", "profile-personal.webp"],
    ["/settings", "settings", "settings-calm.webp", "settings-calm.webp"],
    ["/settings/account", "settings", "settings-calm.webp", "settings-calm.webp"],
    ["/archive", "archive", "archive-hall.webp", "archive-hall.webp"],
    ["/archive/2026", "archive", "archive-hall.webp", "archive-hall.webp"],
    ["/tag/typescript", "tag", "tag-trace.webp", "tag-trace.webp"],
    ["/post/hello-world", "post", "post-ruins.webp", "post-ruins.webp"],
  ] as const;

  it.each(routeCases)(
    "maps %s to %s",
    (pathname, expectedId, expectedHeroFile, expectedPageFile) => {
      const config = getRouteVisualConfig(pathname);

      expect(config?.id).toBe(expectedId);
      expect(config?.hero?.image.split("/").at(-1) ?? null).toBe(expectedHeroFile);
      expect(config?.page.image.split("/").at(-1)).toBe(expectedPageFile);
    },
  );

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
      ROUTE_VISUALS.flatMap(({ hero, page }) => [hero?.image, page.image]).filter(
        (image): image is string => image !== undefined,
      ),
    );

    for (const image of images) {
      const publicPath = path.resolve(process.cwd(), "public", image.slice(1));
      expect(existsSync(publicPath), `${image} should exist under public`).toBe(true);
    }
  });
});
