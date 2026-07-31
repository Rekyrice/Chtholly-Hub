import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

type PackageManifest = {
  dependencies?: Record<string, string>;
  peerDependencies?: Record<string, string>;
};

function readManifest(path: string): PackageManifest {
  return JSON.parse(readFileSync(path, "utf8")) as PackageManifest;
}

function majorOf(range: string | undefined): number | null {
  const match = range?.match(/\d+/u);
  return match ? Number(match[0]) : null;
}

describe("Live2D dependency compatibility", () => {
  it("uses the same Pixi major as pixi-live2d-display", () => {
    const webRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
    const webManifest = readManifest(resolve(webRoot, "package.json"));
    const live2dManifest = readManifest(
      resolve(webRoot, "node_modules/pixi-live2d-display/package.json"),
    );

    expect(majorOf(webManifest.dependencies?.["pixi.js"])).toBe(
      majorOf(live2dManifest.peerDependencies?.["@pixi/core"]),
    );
  });
});
