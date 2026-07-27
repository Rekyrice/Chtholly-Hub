import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("root layout font loading", () => {
  it("uses local system font stacks without requiring Google Fonts at build time", () => {
    const layout = readFileSync(resolve(process.cwd(), "app/layout.tsx"), "utf8");

    expect(layout).not.toContain("next/font/google");
    expect(layout).toContain('"Microsoft YaHei"');
    expect(layout).toContain('"Segoe UI"');
    expect(layout).toContain('"Hiragino Sans"');
    expect(layout).toContain('"Cascadia Code"');
  });
});
