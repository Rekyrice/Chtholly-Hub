import { afterEach, describe, expect, it, vi } from "vitest";

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
    expect(production.distDir).toBe(".next");
  });
});
