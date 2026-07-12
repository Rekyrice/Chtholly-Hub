import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const globals = readFileSync("app/globals.css", "utf8");
const visuals = readFileSync("app/styles/route-visuals.css", "utf8");
const feed = readFileSync("app/styles/feed.css", "utf8");

describe("route visual style contract", () => {
  it("loads the route layer after the existing responsive styles", () => {
    expect(globals).toContain('@import "./styles/route-visuals.css";');
    expect(globals.indexOf("route-visuals.css")).toBeGreaterThan(globals.indexOf("responsive.css"));
    expect(visuals.trimStart()).toMatch(/^@layer utilities\s*\{/);
  });

  it("defines one shared high-transparency surface system", () => {
    expect(globals).toContain("--surface-nav-alpha: 0.71");
    expect(globals).toContain("--surface-panel-alpha: 0.52");
    expect(globals).toContain("--surface-card-alpha: 0.54");
    expect(globals).toContain("--surface-sidebar-alpha: 0.48");
    expect(globals).toContain("--surface-reading-alpha: 0.78");
    expect(globals).toContain("--surface-backdrop-blur: 8px");
    expect(visuals).not.toMatch(/data-route-visual[^\{]*\{[^}]*--color-/);
  });

  it("keeps the page image visible without sacrificing footer readability", () => {
    expect(visuals).toMatch(/\.route-page-background__overlay\s*\{[\s\S]*?var\(--color-surface\)[\s\S]*?var\(--route-bg-overlay\)/);
    expect(visuals).toMatch(/\.site-shell--route-visual \.main-content\s*\{[^}]*background:\s*transparent/);
    expect(visuals).not.toMatch(/\.site-shell--route-visual\s+(?:\.site-footer|footer)\s*\{[^}]*background:\s*transparent/);
  });

  it("preserves both navbar states", () => {
    expect(visuals).toMatch(/\.site-shell--route-visual \.sakuga-navbar\s*\{[\s\S]*?--surface-nav-alpha/);
    expect(visuals).toMatch(/\.site-shell--route-visual \.sakuga-navbar--scrolled\s*\{/);
  });

  it("maps cards, recommendation, and sidebar to their intended shared tokens", () => {
    expect(visuals).toMatch(/\.site-shell--route-visual \.post-card,[\s\S]*?\.site-shell--route-visual \.search-sidebar > \*\s*\{[\s\S]*?--surface-card-alpha/);
    expect(visuals).toMatch(/\.site-shell--route-visual \.hub-sidebar \.widget\s*\{[\s\S]*?--surface-sidebar-alpha/);
    expect(visuals).toMatch(/\.site-shell--route-visual \.hub-recommendation__content,[\s\S]*?--surface-reading-alpha/);
    expect(feed).toContain(".hub-recommendation");
    expect(feed).toContain(".hub-sidebar .widget");
    expect(feed).toContain(".hub-profile-widget");
    expect(feed).toContain(".sidebar-observation-widget");
    expect(feed).toContain(".hub-quick-links-widget");
    expect(feed).toContain(".sidebar-hot-posts");
  });

  it("keeps the 308px sidebar and content-sized feed flow", () => {
    expect(feed).toMatch(/@media \(min-width:\s*1024px\)[\s\S]*?\.hub-timeline-layout\s*\{[\s\S]*?grid-template-columns:\s*minmax\(0,\s*1fr\) 308px;[\s\S]*?align-items:\s*start/);
    expect(feed).toMatch(/\.hub-sidebar\s*\{[^}]*align-self:\s*start/);
    expect(feed).toMatch(/\.hub-feed-list\s*\{[^}]*display:\s*grid;[^}]*align-content:\s*start;[^}]*gap:\s*var\(--post-card-gap\)/);
    expect(feed).toMatch(/\.hub-feed-list \.post-card\s*\{[^}]*margin-bottom:\s*0/);
  });
});
