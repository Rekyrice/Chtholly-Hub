import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

describe("root not-found bundle contract", () => {
  it("does not statically import the Agent provider", () => {
    const source = readFileSync(resolve(process.cwd(), "app/not-found.tsx"), "utf8");

    expect(source).not.toContain("AgentChatProvider");
  });
});
