import { readFileSync } from "node:fs";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import SiteHeader from "@/components/site/SiteHeader";

vi.mock("@/components/site/HeroParticles", () => ({
  default: () => <div data-testid="hero-particles" />,
}));
vi.mock("@/components/site/HeroTypewriter", () => ({
  default: () => <div data-testid="hero-typewriter" />,
}));

const background = {
  image: "/images/site/backgrounds/hub-hero.webp",
  positionDesktop: "52% 45%",
  positionMobile: "58% 44%",
  overlayAlpha: 0.18,
  blurPx: 0,
  saturate: 0.98,
} as const;

describe("SiteHeader", () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    window.matchMedia = vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    });
  });

  it("keeps the existing hero content and exposes route variables from the shared ancestor", () => {
    render(<SiteHeader background={background} />);

    const header = screen.getByTestId("site-header");
    const backgroundLayer = screen.getByTestId("site-header-background");
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Chtholly Hub");
    expect(screen.getByTestId("hero-particles")).toBeInTheDocument();
    expect(screen.getByTestId("hero-typewriter")).toBeInTheDocument();
    expect(header).toHaveStyle({
      "--site-header-image": 'url("/images/site/backgrounds/hub-hero.webp")',
      "--site-header-position": "52% 45%",
      "--site-header-position-mobile": "58% 44%",
      "--site-header-overlay": "0.18",
      "--site-header-blur": "0px",
      "--site-header-saturate": "0.98",
    });
    expect(backgroundLayer).toHaveStyle({ transform: "translate3d(0, 0px, 0)" });
    expect(backgroundLayer.style.getPropertyValue("--site-header-overlay")).toBe("");

    const childClasses = Array.from(header.children).map((child) => child.className);
    expect(childClasses).toEqual([
      "site-header-bg",
      "",
      "site-header-overlay",
      "site-header-content",
    ]);
  });

  it("keeps the gradient fallback when no route background is provided", () => {
    render(<SiteHeader />);

    const header = screen.getByTestId("site-header");
    expect(header.style.getPropertyValue("--site-header-image")).toBe("");
    expect(screen.getByRole("heading", { level: 1 })).toBeInTheDocument();
    expect(screen.getByTestId("hero-particles")).toBeInTheDocument();
    expect(screen.getByTestId("hero-typewriter")).toBeInTheDocument();
  });

  it("preserves the approved notch, typography, and overlay CSS contract", () => {
    const css = readFileSync("app/styles/navbar.css", "utf8");

    expect(css).toMatch(/\.site-header\s*\{[\s\S]*?height:\s*480px/);
    expect(css).toMatch(/\.site-header\s*\{[^}]*z-index:\s*1/);
    expect(css).toMatch(/\.site-header\s*\{[\s\S]*?mask-image:\s*linear-gradient/);
    expect(css).toMatch(/\.site-header-title\s*\{[\s\S]*?font-size:\s*63px/);
    expect(css).toMatch(/\.site-header-desc\s*\{[\s\S]*?font-size:\s*18px/);
    expect(css).toMatch(/\.site-header-bg\s*\{[\s\S]*?var\(--site-header-blur,\s*0px\)[\s\S]*?var\(--site-header-saturate,\s*1\)/);
    expect(css).toMatch(/\.site-header-overlay\s*\{[\s\S]*?var\(--site-header-overlay,\s*var\(--blog-header-overlay\)\)/);
  });
});
