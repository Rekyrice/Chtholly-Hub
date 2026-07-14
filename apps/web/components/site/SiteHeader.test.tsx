import { readFileSync } from "node:fs";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import SiteHeader from "@/components/site/SiteHeader";

vi.mock("@/components/site/HeroParticles", () => ({
  default: () => <div data-testid="hero-particles" />,
}));
vi.mock("@/components/site/HeroTypewriter", () => ({
  default: ({ onLineTransition }: { onLineTransition?: (index: number, durationMs: number) => void }) => (
    <button data-testid="hero-typewriter" onClick={() => onLineTransition?.(2, 3200)} />
  ),
}));

describe("SiteHeader", () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    window.matchMedia = vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    });
  });

  it("keeps the existing hero structure and forwards quote changes", () => {
    const onQuoteTransition = vi.fn();
    render(<SiteHeader onQuoteTransition={onQuoteTransition} />);

    const header = screen.getByTestId("site-header");
    const backgroundLayer = screen.getByTestId("site-header-background");
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Chtholly Hub");
    expect(screen.getByTestId("hero-particles")).toBeInTheDocument();
    expect(screen.getByTestId("hero-typewriter")).toBeInTheDocument();
    screen.getByTestId("hero-typewriter").click();
    expect(onQuoteTransition).toHaveBeenCalledWith(2, 3200);
    expect(backgroundLayer).toHaveStyle({ transform: "translate3d(0, 0px, 0)" });
    for (const property of ["image", "position", "position-mobile", "blur", "saturate"]) {
      expect(header.style.getPropertyValue(`--site-header-${property}`)).toBe("");
    }

    const childClasses = Array.from(header.children).map((child) => child.className);
    expect(childClasses).toEqual([
      "site-header-bg",
      "",
      "site-header-overlay",
      "site-header-content",
    ]);
  });

  it("preserves the approved notch, typography, and overlay CSS contract", () => {
    const css = readFileSync("app/styles/navbar.css", "utf8");

    expect(css).toMatch(/\.site-header\s*\{[\s\S]*?height:\s*480px/);
    expect(css).toMatch(/\.site-header\s*\{[^}]*z-index:\s*1/);
    expect(css).toMatch(/\.site-header\s*\{[\s\S]*?mask-image:\s*linear-gradient/);
    expect(css).toMatch(/\.site-header-title\s*\{[\s\S]*?font-size:\s*63px/);
    expect(css).toMatch(/\.site-header-desc\s*\{[\s\S]*?font-size:\s*18px/);
    expect(css).toMatch(
      /\.site-header-overlay\s*\{[\s\S]*?var\(--site-header-overlay,\s*var\(--blog-header-overlay\)\)/,
    );
    expect(css).toMatch(/\.site-header-overlay\s*\{[\s\S]*?rgb\(24 28 36 \/ var/);
  });

});
