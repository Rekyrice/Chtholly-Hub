import { readFileSync } from "node:fs";
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
});
