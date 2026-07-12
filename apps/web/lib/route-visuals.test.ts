import { existsSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

import { NOT_FOUND_VISUAL, ROUTE_VISUALS, getRouteVisualConfig } from "./route-visuals";

describe("route visuals", () => {
  const routeCases = [
    ["/hub", "hub", ["hub-01.webp", "hub-02.webp", "hub-03.webp"]],
    ["/search", "search", ["search.webp"]],
    ["/write", "write", ["write.webp"]],
    ["/login", "login", ["login.webp"]],
    ["/reset-password", "reset-password", ["reset-password.webp"]],
    ["/about", "about", ["about.webp"]],
    ["/user", "user", ["user.webp"]],
    ["/profile", "profile", ["search.webp"]],
    ["/settings", "settings", ["settings.webp"]],
    ["/archive", "archive", ["archive.webp"]],
    ["/tag", "tag", ["tag.webp"]],
    ["/post", "post", ["post.webp"]],
    ["/admin", "admin", ["admin.webp"]],
  ] as const;

  it.each(routeCases)("maps %s to %s", (pathname, expectedId, expectedFiles) => {
    const config = getRouteVisualConfig(pathname);

    expect(config?.id).toBe(expectedId);
    expect(config?.page.images.map((image) => image.split("/").at(-1))).toEqual(expectedFiles);
  });

  it.each(["/", "/agent", "/agent/history", "/chtholly", "/chtholly/chat"])(
    "does not decorate excluded route %s",
    (pathname) => {
      expect(getRouteVisualConfig(pathname)).toBeNull();
    },
  );

  it.each(["/searching", "/writing", "/administrator", "/profiles"])(
    "does not match colliding prefix %s",
    (pathname) => {
      expect(getRouteVisualConfig(pathname)).toBeNull();
    },
  );

  it.each([
    ["/hub/topic", "hub"],
    ["/reset-password/token", "reset-password"],
    ["/user/alice", "user"],
    ["/admin/posts", "admin"],
  ])("matches nested route %s", (pathname, expectedId) => {
    expect(getRouteVisualConfig(pathname)?.id).toBe(expectedId);
  });

  it("exports the dedicated not-found visual", () => {
    expect(NOT_FOUND_VISUAL.id).toBe("not-found");
    expect(NOT_FOUND_VISUAL.page.images).toEqual([
      "/images/site/backgrounds/not-found.webp",
    ]);
  });

  it("assigns a unique id and only formal image paths to each visual", () => {
    const ids = new Set(ROUTE_VISUALS.map(({ id }) => id));

    expect(ids.size).toBe(ROUTE_VISUALS.length);
    for (const { page } of [...ROUTE_VISUALS, NOT_FOUND_VISUAL]) {
      expect(page.images.length).toBeGreaterThan(0);
      for (const image of page.images) {
        expect(image).toMatch(/^\/images\/site\/backgrounds\//);
        expect(image).not.toContain("_incoming");
      }
    }
  });

  it("deeply freezes the exported visual configuration graph", () => {
    expect(Object.isFrozen(ROUTE_VISUALS)).toBe(true);

    for (const config of [...ROUTE_VISUALS, NOT_FOUND_VISUAL]) {
      expect(Object.isFrozen(config)).toBe(true);
      expect(Object.isFrozen(config.page)).toBe(true);
      expect(Object.isFrozen(config.page.images)).toBe(true);
    }
  });

  it.each([
    ["/hub/", "hub"],
    ["/write/", "write"],
    ["/admin/", "admin"],
  ])("matches trailing slash route %s", (pathname, expectedId) => {
    expect(getRouteVisualConfig(pathname)?.id).toBe(expectedId);
  });

  it("uses conservative, complete page focal parameters", () => {
    for (const { page } of [...ROUTE_VISUALS, NOT_FOUND_VISUAL]) {
      expect(page.positionDesktop).toBeTruthy();
      expect(page.positionMobile).toBeTruthy();
      expect(page.overlayAlpha).toBeGreaterThanOrEqual(0);
      expect(page.overlayAlpha).toBeLessThanOrEqual(1);
      expect(page.blurPx).toBeGreaterThanOrEqual(0);
      expect(page.saturate).toBeGreaterThan(0);
    }
  });

  it("keeps the user background portrait face inside desktop and mobile crops", () => {
    const userVisual = getRouteVisualConfig("/user/Rekyrice");

    expect(userVisual?.page.positionDesktop).toBe("55% 22%");
    expect(userVisual?.page.positionMobile).toBe("56% 28%");
  });

  it("references public files that exist", () => {
    const images = new Set(
      [...ROUTE_VISUALS, NOT_FOUND_VISUAL].flatMap(({ page }) => page.images),
    );

    for (const image of images) {
      const publicPath = path.resolve(process.cwd(), "public", image.slice(1));
      expect(existsSync(publicPath), `${image} should exist under public`).toBe(true);
    }
  });
});
