import { afterEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import tsconfig from "./tsconfig.json";

async function loadConfig(nodeEnv: "development" | "production") {
  vi.stubEnv("NODE_ENV", nodeEnv);
  vi.resetModules();
  return (await import("./next.config")).default;
}

describe("Next.js build output isolation", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it("keeps development artifacts separate from production build artifacts", async () => {
    const development = await loadConfig("development");
    const production = await loadConfig("production");

    expect(development.distDir).toBe(".next-dev");
    expect(production.distDir).toBe(".next-prod");
  });

  it("does not type-check legacy development declarations from the production directory", () => {
    expect(tsconfig.include).not.toContain(".next/dev/types/**/*.ts");
    expect(tsconfig.include).toContain(".next-dev/dev/types/**/*.ts");
    expect(tsconfig.include).toContain(".next-prod/dev/types/**/*.ts");
  });

  it("packages standalone and static assets from the production output directory", () => {
    const dockerfile = readFileSync(resolve(process.cwd(), "Dockerfile"), "utf8");

    expect(dockerfile).toContain("/app/.next-prod/standalone");
    expect(dockerfile).toContain("/app/.next-prod/static");
    expect(dockerfile).toContain("./.next-prod/static");
  });
});
