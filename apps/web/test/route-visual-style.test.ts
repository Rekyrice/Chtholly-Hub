import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const globals = readFileSync("app/globals.css", "utf8");
const visuals = readFileSync("app/styles/route-visuals.css", "utf8");
const feed = readFileSync("app/styles/feed.css", "utf8");
const write = readFileSync("app/styles/write.css", "utf8");
const settings = readFileSync("app/styles/settings.css", "utf8");
const responsive = readFileSync("app/styles/responsive.css", "utf8");

describe("route visual style contract", () => {
  it("loads the route layer after the existing responsive styles", () => {
    expect(globals).toContain('@import "./styles/route-visuals.css";');
    expect(globals.indexOf("route-visuals.css")).toBeGreaterThan(globals.indexOf("responsive.css"));
    expect(visuals.trimStart()).toMatch(/^@layer utilities\s*\{/);
  });

  it("defines one shared high-transparency surface system", () => {
    expect(globals).not.toContain("--surface-card-alpha");
    expect(visuals).toMatch(/\.site-shell--route-visual\s*\{[\s\S]*?--surface-nav-alpha:\s*0\.71;[\s\S]*?--surface-panel-alpha:\s*0\.52;[\s\S]*?--surface-card-alpha:\s*0\.54;[\s\S]*?--surface-sidebar-alpha:\s*0\.48;[\s\S]*?--surface-reading-alpha:\s*0\.78;[\s\S]*?--surface-editor-alpha:\s*0\.78;[\s\S]*?--surface-backdrop-blur:\s*8px;/);
    expect(visuals).not.toMatch(/data-route-visual[^\{]*\{[^}]*--color-[\w-]+\s*:/);
  });

  it("applies the no-blur readability fallback after mobile transparency overrides", () => {
    expect(visuals.lastIndexOf("@supports not")).toBeGreaterThan(
      visuals.lastIndexOf("@media (max-width: 767px)"),
    );
    expect(visuals).toMatch(/@supports not[\s\S]*?--surface-card-alpha:\s*0\.88;[\s\S]*?--surface-sidebar-alpha:\s*0\.84;/);
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

  it("protects editor, auth, and settings readability without reusing landing artwork", () => {
    expect(write).not.toContain('/images/landing/default.jpg');
    expect(settings).not.toContain('/images/landing/default.jpg');
    expect(write).toMatch(/\.write-editor-wrapper\s*\{[^}]*--surface-editor-alpha/);
    expect(settings).toMatch(/\.settings-menu__item\s*\{[^}]*--surface-auth-alpha/);
    expect(settings).toMatch(/\.settings-loading,[^{]*\.settings-form-panel\s*\{[^}]*--surface-auth-alpha/);
    expect(settings).not.toMatch(/\.settings-menu\s*\{[^}]*background:/);
    expect(settings).toMatch(/\.settings-menu__item:hover\s*\{[^}]*box-shadow:\s*var\(--surface-shadow\)/);
    expect(visuals).toMatch(/\[data-route-visual="auth"\] \.post-card\s*\{[^}]*--surface-auth-alpha/);
    expect(visuals).toMatch(/\.write-preview\.prose-anime\s*\{[^}]*--surface-editor-alpha/);
  });

  it("keeps mobile route surfaces readable in the final cascade", () => {
    const mobileStart = visuals.indexOf("@media (max-width: 767px)");
    const mobileEnd = visuals.indexOf("@media (prefers-reduced-motion: reduce)", mobileStart);
    const mobileRules = visuals.slice(mobileStart, mobileEnd);

    expect(responsive).toMatch(/\.site-shell\s*\{[^}]*padding-bottom:[^}]*safe-area-inset-bottom/);
    expect(mobileStart).toBeGreaterThan(-1);
    expect(mobileEnd).toBeGreaterThan(mobileStart);
    expect(mobileRules).toMatch(/\.site-shell--route-visual \.post-card,\s*\.site-shell--route-visual \.hub-sidebar \.widget\s*\{[^}]*backdrop-filter:\s*blur\(7px\) saturate\(0\.72\);[^}]*-webkit-backdrop-filter:\s*blur\(7px\) saturate\(0\.72\)/);
  });
});
