import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { MOTION_BOOTSTRAP_SCRIPT } from "@/lib/motion-bootstrap";

function runBootstrap({
  hasIntersectionObserver,
  prefersReducedMotion,
}: {
  hasIntersectionObserver: boolean;
  prefersReducedMotion: boolean;
}) {
  const documentElement = {
    dataset: {} as Record<string, string>,
  };
  const windowMock = {
    matchMedia: () => ({ matches: prefersReducedMotion }),
    ...(hasIntersectionObserver ? { IntersectionObserver: class {} } : {}),
  };

  new Function("window", "document", MOTION_BOOTSTRAP_SCRIPT)(windowMock, {
    documentElement,
  });

  return documentElement.dataset.motionReady;
}

describe("motion bootstrap", () => {
  it("sets the pre-paint marker only when entrance animation is supported", () => {
    expect(
      runBootstrap({
        hasIntersectionObserver: true,
        prefersReducedMotion: false,
      }),
    ).toBe("true");

    expect(
      runBootstrap({
        hasIntersectionObserver: false,
        prefersReducedMotion: false,
      }),
    ).toBeUndefined();

    expect(
      runBootstrap({
        hasIntersectionObserver: true,
        prefersReducedMotion: true,
      }),
    ).toBeUndefined();
  });

  it("is installed synchronously in the root document head", () => {
    const layoutSource = readFileSync(
      resolve(process.cwd(), "app/layout.tsx"),
      "utf8",
    );

    expect(layoutSource).toContain("MOTION_BOOTSTRAP_SCRIPT");
    expect(layoutSource).toMatch(
      /<head>[\s\S]*?<script[\s\S]*?MOTION_BOOTSTRAP_SCRIPT[\s\S]*?<\/head>/,
    );
  });
});
