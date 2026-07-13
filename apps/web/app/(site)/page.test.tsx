import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

import { metadata } from "./page";

const formalLandingImage = "/images/landing/home.webp";

describe("landing page", () => {
  it("uses the same formal image for share metadata and the landing background", () => {
    const landingCss = readFileSync("app/styles/landing.css", "utf8");

    expect(metadata.openGraph?.images).toEqual([formalLandingImage]);
    expect(metadata.twitter?.images).toEqual([formalLandingImage]);
    expect(landingCss).toContain(`background-image: url("${formalLandingImage}")`);
    expect(landingCss).not.toContain('/images/landing/default.jpg');
  });

  it("keeps the redesigned Chtholly room independent from landing imagery", () => {
    const communityCss = readFileSync("app/styles/community.css", "utf8");

    expect(communityCss).not.toContain('/images/landing/default.jpg');
    expect(communityCss).not.toContain(formalLandingImage);
  });
});
