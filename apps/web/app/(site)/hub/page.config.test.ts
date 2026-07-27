import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("Hub page caching", () => {
  it("does not share a stale public feed page after visibility changes", () => {
    const source = readFileSync(resolve(process.cwd(), "app/(site)/hub/page.tsx"), "utf8");

    expect(source).toContain('export const dynamic = "force-dynamic"');
    expect(source).not.toContain("export const revalidate = 60");
  });
});
