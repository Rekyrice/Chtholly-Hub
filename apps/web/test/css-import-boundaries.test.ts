import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

function source(relativePath: string) {
  return readFileSync(resolve(process.cwd(), relativePath), "utf8");
}

describe("route CSS import boundaries", () => {
  it("keeps route-specific styles out of globals.css", () => {
    const globals = source("app/globals.css");

    for (const stylesheet of ["agent.css", "landing.css", "write.css", "admin.css"]) {
      expect(globals).not.toContain(stylesheet);
    }
  });

  it.each([
    ["landing", "app/(site)/page.tsx", 'import "../styles/landing.css";'],
    ["write", "app/(site)/write/page.tsx", 'import "../../styles/write.css";'],
    ["admin", "app/(site)/admin/layout.tsx", 'import "../../styles/admin.css";'],
    ["agent", "app/(site)/agent/layout.tsx", 'import "../../styles/agent.css";'],
    ["chtholly", "app/(site)/chtholly/page.tsx", 'import "../../styles/agent.css";'],
  ])("loads %s styles from its route entry", (_route, entry, cssImport) => {
    expect(source(entry)).toContain(cssImport);
  });

  it("loads agent styles with the authenticated floating runtime chunk", () => {
    expect(source("components/agent/AuthenticatedAgentRuntime.tsx")).toContain(
      'import "../../app/styles/agent.css";',
    );
  });

  it("keeps shared shell and write mobile rules with their owners", () => {
    const agent = source("app/styles/agent.css");
    const responsive = source("app/styles/responsive.css");
    const write = source("app/styles/write.css");

    expect(agent).not.toMatch(/^\s*\.site-shell\s*\{/m);
    for (const selector of [
      "write-container",
      "write-editor-wrapper",
      "write-status",
      "write-meta-row",
      "write-toolbar",
      "write-mode-toggle",
      "write-mode-btn",
      "write-editor",
    ]) {
      expect(agent).not.toMatch(new RegExp(`^\\s*\\.${selector}\\s*\\{`, "m"));
      expect(write).toMatch(new RegExp(`^\\s*\\.${selector}\\s*\\{`, "m"));
    }

    expect(responsive).toMatch(/@media \(max-width: 767px\) \{[\s\S]*\.site-shell \{/);
    expect(write).toMatch(/@media \(max-width: 767px\) \{[\s\S]*\.write-container \{/);
  });

  it("keeps route reduced-motion overrides after route motion declarations", () => {
    const responsive = source("app/styles/responsive.css");
    const landing = source("app/styles/landing.css");
    const agent = source("app/styles/agent.css");
    const write = source("app/styles/write.css");
    const admin = source("app/styles/admin.css");

    const responsiveReducedMotion = responsive.slice(
      responsive.indexOf("@media (prefers-reduced-motion: reduce)"),
    );
    for (const selector of [
      "landing-background__image",
      "landing-typewriter",
      "landing-typewriter__cursor",
      "not-found-illustration",
      "not-found-btn",
      "agent-message-row--assistant-enter",
      "agent-message-row--user-enter",
      "proactive-notification",
    ]) {
      expect(responsiveReducedMotion).not.toContain(`.${selector}`);
    }
    expect(responsiveReducedMotion).not.toMatch(/^\s*\.(?:write|admin)-/m);

    const landingReducedMotion = landing.slice(
      landing.lastIndexOf("@media (prefers-reduced-motion: reduce)"),
    );
    expect(landing.lastIndexOf("@media (prefers-reduced-motion: reduce)")).toBeGreaterThan(
      landing.lastIndexOf("animation: landing-zoom"),
    );
    expect(landingReducedMotion).toContain(".landing-background__image");
    expect(landingReducedMotion).toContain(".landing-typewriter__cursor");

    const agentReducedMotion = agent.slice(
      agent.lastIndexOf("@media (prefers-reduced-motion: reduce)"),
    );
    expect(agent.lastIndexOf("@media (prefers-reduced-motion: reduce)")).toBeGreaterThan(
      agent.lastIndexOf("animation: proactive-slide-up"),
    );
    expect(agentReducedMotion).toContain(".chtholly-illustration");
    expect(agentReducedMotion).toContain(".agent-message-row--assistant-enter");
    expect(agentReducedMotion).toContain(".proactive-notification");

    expect(write).not.toContain("@media (prefers-reduced-motion: reduce)");
    expect(admin).not.toContain("@media (prefers-reduced-motion: reduce)");
  });

  it("keeps not-found styles in a dedicated owner", () => {
    const notFoundPath = resolve(process.cwd(), "app/styles/not-found.css");
    expect(existsSync(notFoundPath)).toBe(true);
    if (!existsSync(notFoundPath)) return;

    const landing = source("app/styles/landing.css");
    const notFound = readFileSync(notFoundPath, "utf8");
    expect(landing).not.toMatch(/^\s*\.not-found-/m);
    expect(landing).not.toContain("@keyframes not-found-float");
    for (const selector of [
      "not-found-page",
      "not-found-content",
      "not-found-illustration",
      "not-found-title",
      "not-found-message",
      "not-found-submessage",
      "not-found-btn",
    ]) {
      expect(notFound).toContain(`.${selector}`);
    }
    for (const selector of [
      "not-found-background",
      "not-found-background__image",
      "not-found-background__scrim",
    ]) {
      expect(notFound).not.toContain(`.${selector}`);
    }
    expect(notFound).not.toContain('/images/landing/default.jpg');
    expect(notFound).toContain("@keyframes not-found-float");
    expect(notFound).toMatch(
      /@media \(prefers-reduced-motion: reduce\) \{[\s\S]*\.not-found-illustration/,
    );
  });

  it("loads not-found styles through the shared not-found component", () => {
    const rootNotFound = source("app/not-found.tsx");
    const siteNotFound = source("app/(site)/not-found.tsx");

    expect(rootNotFound).toContain('import SiteNotFound from "./(site)/not-found";');
    expect(siteNotFound).toContain('import "../styles/not-found.css";');
  });
});
