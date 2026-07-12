import assert from "node:assert/strict";
import { mkdir, readFile, rm, stat, symlink, writeFile } from "node:fs/promises";
import path from "node:path";
import { before, test } from "node:test";
import { fileURLToPath } from "node:url";

import sharp from "sharp";
import YAML from "yaml";

import { prepareOne, preparePack } from "./prepare-content-media.mjs";

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const repositoryRoot = path.resolve(webRoot, "../..");
const testRoot = path.join(repositoryRoot, ".codex-tmp", "seed-content-v2-tests");

async function writeSvg(file, width = 800, height = 600, color = "#7c3aed") {
  await mkdir(path.dirname(file), { recursive: true });
  await writeFile(
    file,
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><rect width="100%" height="100%" fill="${color}"/></svg>`,
  );
}

async function writePack(packRoot, assets, version = "content-v2") {
  await mkdir(packRoot, { recursive: true });
  await writeFile(path.join(packRoot, "manifest.yml"), YAML.stringify({ version }));
  await writeFile(path.join(packRoot, "assets.yml"), YAML.stringify(assets));
}

before(async () => {
  await rm(testRoot, { recursive: true, force: true });
  await mkdir(testRoot, { recursive: true });
});

test("prepares an alpha-preserving WebP avatar at the explicit output and records sha256", async () => {
  const fixtureSvg = path.join(testRoot, "prepare-one", "avatar.svg");
  const outputWebp = path.join(testRoot, "prepare-one", "avatar.webp");
  await mkdir(path.dirname(fixtureSvg), { recursive: true });
  await writeFile(fixtureSvg, '<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600"><rect width="100%" height="100%" fill="#7c3aed" fill-opacity="0.5"/></svg>');

  const result = await prepareOne({
    input: fixtureSvg,
    output: outputWebp,
    width: 512,
    height: 512,
    fit: "cover",
  });

  const meta = await sharp(outputWebp).metadata();
  assert.equal(meta.format, "webp");
  assert.equal(meta.width, 512);
  assert.equal(meta.height, 512);
  assert.equal(meta.hasAlpha, true);
  assert.match(result.sha256, /^[a-f0-9]{64}$/);
  assert.equal(result.width, 512);
  assert.equal(result.height, 512);
});

test("normalizes usages, writes hashes, and emits a stable sorted report", async () => {
  const projectRoot = path.join(testRoot, "normalize-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v2");
  const sourcesRoot = path.join(projectRoot, ".codex-tmp", "seed-content-v2", "sources");
  await writeSvg(path.join(sourcesRoot, "wide.svg"), 2400, 1200, "#123456");
  await writeSvg(path.join(sourcesRoot, "portrait.svg"), 600, 900, "#abcdef");
  await writePack(packRoot, [
    { key: "z-inline", source: "local", sourceUrl: "https://example.test/provenance", sourceFile: "wide.svg", file: "inline/z.webp", objectKey: "seed/content-v2/inline/z.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "inline" },
    { key: "a-avatar", source: "local", sourceFile: "portrait.svg", file: "avatars/a.webp", objectKey: "seed/content-v2/avatars/a.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" },
    { key: "m-cover", source: "local", sourceFile: "wide.svg", file: "covers/m.webp", objectKey: "seed/content-v2/covers/m.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "cover" },
  ]);

  const first = await preparePack({ packRoot, projectRoot, writeHashes: true });
  const firstYaml = await readFile(path.join(packRoot, "assets.yml"), "utf8");
  const firstBytes = await readFile(path.join(packRoot, "assets", "covers", "m.webp"));
  const second = await preparePack({ packRoot, projectRoot, writeHashes: true });

  assert.deepEqual(first.assets.map((asset) => asset.key), ["a-avatar", "m-cover", "z-inline"]);
  assert.deepEqual(second.assets, first.assets);
  assert.equal(await readFile(path.join(packRoot, "assets.yml"), "utf8"), firstYaml);
  assert.deepEqual(await readFile(path.join(packRoot, "assets", "covers", "m.webp")), firstBytes);

  const avatar = await sharp(path.join(packRoot, "assets", "avatars", "a.webp")).metadata();
  const cover = await sharp(path.join(packRoot, "assets", "covers", "m.webp")).metadata();
  const inline = await sharp(path.join(packRoot, "assets", "inline", "z.webp")).metadata();
  assert.deepEqual([avatar.width, avatar.height], [512, 512]);
  assert.deepEqual([cover.width, cover.height], [1200, 675]);
  assert.deepEqual([inline.width, inline.height], [1600, 800]);

  const savedAssets = YAML.parse(firstYaml);
  assert.ok(savedAssets.every((asset) => /^[a-f0-9]{64}$/.test(asset.sha256)));
  assert.ok(savedAssets.every((asset) => asset.contentType === "image/webp"));
  assert.equal(first.assets.at(-1).sourceUrl, "https://example.test/provenance");
  const reportPath = path.join(projectRoot, ".codex-tmp", "seed-content-v2", "media-report.json");
  assert.deepEqual(JSON.parse(await readFile(reportPath, "utf8")), second);
});

test("uses a non-empty content-v3 manifest for sources, hashes, checks, and reports", async () => {
  const projectRoot = path.join(testRoot, "content-v3-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v3");
  const source = path.join(projectRoot, ".codex-tmp", "seed-content-v3", "sources", "avatar.svg");
  await writeSvg(source);
  await writePack(packRoot, [
    { key: "avatar", source: "local", sourceFile: "avatar.svg", file: "avatars/avatar.webp", objectKey: "seed/content-v3/avatars/avatar.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" },
  ], "content-v3");

  const prepared = await preparePack({ packRoot, projectRoot, writeHashes: true });
  const checked = await preparePack({ packRoot, projectRoot, check: true });

  assert.deepEqual(checked, prepared);
  const savedAssets = YAML.parse(await readFile(path.join(packRoot, "assets.yml"), "utf8"));
  assert.match(savedAssets[0].sha256, /^[a-f0-9]{64}$/);
  assert.deepEqual(
    JSON.parse(await readFile(path.join(projectRoot, ".codex-tmp", "seed-content-v3", "media-report.json"), "utf8")),
    prepared,
  );
});

test("rejects unsupported and unsafe manifest versions before resolving temp paths", async () => {
  const projectRoot = path.join(testRoot, "unsafe-version-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v3");
  for (const version of ["content-beta", "content-v0", "../outside"]) {
    await writePack(packRoot, [], version);
    await assert.rejects(
      () => preparePack({ packRoot, projectRoot, writeHashes: true }),
      /unsafe content pack version/i,
    );
  }
});

test("check mode validates without modifying outputs or assets.yml", async () => {
  const projectRoot = path.join(testRoot, "check-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v2");
  const source = path.join(projectRoot, ".codex-tmp", "seed-content-v2", "sources", "avatar.svg");
  await writeSvg(source);
  await writePack(packRoot, [
    { key: "avatar", source: "local", sourceFile: "avatar.svg", file: "avatars/avatar.webp", objectKey: "seed/content-v2/avatars/avatar.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" },
  ]);
  await preparePack({ packRoot, projectRoot, writeHashes: true });
  const yamlBefore = await readFile(path.join(packRoot, "assets.yml"), "utf8");
  const output = path.join(packRoot, "assets", "avatars", "avatar.webp");
  const modifiedBefore = (await stat(output)).mtimeMs;
  const report = path.join(projectRoot, ".codex-tmp", "seed-content-v2", "media-report.json");
  const reportBefore = await readFile(report, "utf8");
  const reportModifiedBefore = (await stat(report)).mtimeMs;

  await preparePack({ packRoot, projectRoot, check: true });

  assert.equal(await readFile(path.join(packRoot, "assets.yml"), "utf8"), yamlBefore);
  assert.equal((await stat(output)).mtimeMs, modifiedBefore);
  assert.equal(await readFile(report, "utf8"), reportBefore);
  assert.equal((await stat(report)).mtimeMs, reportModifiedBefore);
});

test("check mode does not create a report when none exists", async () => {
  const projectRoot = path.join(testRoot, "check-no-report-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v2");
  const source = path.join(projectRoot, ".codex-tmp", "seed-content-v2", "sources", "avatar.svg");
  await writeSvg(source);
  await writePack(packRoot, [
    { key: "avatar", source: "local", sourceFile: "avatar.svg", file: "avatars/avatar.webp", objectKey: "seed/content-v2/avatars/avatar.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" },
  ]);
  await preparePack({ packRoot, projectRoot, writeHashes: true });
  const report = path.join(projectRoot, ".codex-tmp", "seed-content-v2", "media-report.json");
  await rm(report);

  await preparePack({ packRoot, projectRoot, check: true });

  await assert.rejects(() => stat(report), { code: "ENOENT" });
});

test("rejects two declarations that normalize to the same output path", async () => {
  const projectRoot = path.join(testRoot, "normalized-duplicate-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v2");
  const source = path.join(projectRoot, ".codex-tmp", "seed-content-v2", "sources", "avatar.svg");
  await writeSvg(source);
  const base = { source: "local", sourceFile: "avatar.svg", objectKey: "seed/content-v2/avatars/avatar.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" };
  await writePack(packRoot, [
    { ...base, key: "one", file: "avatars/avatar.webp" },
    { ...base, key: "two", file: "avatars/../avatars/avatar.webp" },
  ]);

  await assert.rejects(() => preparePack({ packRoot, projectRoot }), /duplicate output file/i);
});

test("does not replace an earlier output when a later asset fails preprocessing", async () => {
  const projectRoot = path.join(testRoot, "atomic-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v2");
  const sourcesRoot = path.join(projectRoot, ".codex-tmp", "seed-content-v2", "sources");
  const oldOutput = path.join(packRoot, "assets", "avatars", "old.webp");
  const oldBytes = Buffer.from("existing output must survive");
  await writeSvg(path.join(sourcesRoot, "valid.svg"));
  await mkdir(path.dirname(oldOutput), { recursive: true });
  await writeFile(oldOutput, oldBytes);
  await writePack(packRoot, [
    { key: "a-valid", source: "local", sourceFile: "valid.svg", file: "avatars/old.webp", objectKey: "seed/content-v2/avatars/old.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" },
    { key: "z-invalid", source: "local", sourceFile: "missing.svg", file: "avatars/new.webp", objectKey: "seed/content-v2/avatars/new.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" },
  ]);

  await assert.rejects(() => preparePack({ packRoot, projectRoot }), /source file not found/i);

  assert.deepEqual(await readFile(oldOutput), oldBytes);
  await assert.rejects(() => stat(path.join(packRoot, "assets", "avatars", "new.webp")), { code: "ENOENT" });
});

test("rejects a concurrent writer lock without changing it", async () => {
  const projectRoot = path.join(testRoot, "lock-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v2");
  const source = path.join(projectRoot, ".codex-tmp", "seed-content-v2", "sources", "avatar.svg");
  const lock = path.join(packRoot, ".media-prepare.lock");
  await writeSvg(source);
  await writePack(packRoot, [
    { key: "avatar", source: "local", sourceFile: "avatar.svg", file: "avatars/avatar.webp", objectKey: "seed/content-v2/avatars/avatar.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" },
  ]);
  await mkdir(lock);

  await assert.rejects(() => preparePack({ packRoot, projectRoot }), /already being prepared/i);

  assert.equal((await stat(lock)).isDirectory(), true);
});

test("rejects an output directory junction or symlink that escapes the pack", async (context) => {
  const projectRoot = path.join(testRoot, "symlink-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v2");
  const source = path.join(projectRoot, ".codex-tmp", "seed-content-v2", "sources", "avatar.svg");
  const outside = path.join(projectRoot, "outside-assets");
  const linkedAvatars = path.join(packRoot, "assets", "avatars");
  await writeSvg(source);
  await writePack(packRoot, [
    { key: "avatar", source: "local", sourceFile: "avatar.svg", file: "avatars/avatar.webp", objectKey: "seed/content-v2/avatars/avatar.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" },
  ]);
  await mkdir(path.dirname(linkedAvatars), { recursive: true });
  await mkdir(outside, { recursive: true });
  try {
    await symlink(outside, linkedAvatars, process.platform === "win32" ? "junction" : "dir");
  } catch (error) {
    if (["EPERM", "EACCES", "ENOSYS"].includes(error.code)) {
      context.skip(`symlink/junction creation unavailable: ${error.code}`);
      return;
    }
    throw error;
  }

  await assert.rejects(() => preparePack({ packRoot, projectRoot }), /outside the trusted pack/i);
  await assert.rejects(() => stat(path.join(outside, "avatar.webp")), { code: "ENOENT" });
});

test("rolls back installed images when a report target escapes during commit", async (context) => {
  const projectRoot = path.join(testRoot, "commit-rollback-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v2");
  const incomingRoot = path.join(projectRoot, "apps", "web", "public", "images", "_incoming", "pic");
  const oldOutput = path.join(packRoot, "assets", "avatars", "avatar.webp");
  const reportDirectory = path.join(projectRoot, ".codex-tmp", "seed-content-v2");
  const outside = path.join(testRoot, "outside-report");
  const oldBytes = Buffer.from("old image bytes");
  await writeSvg(path.join(incomingRoot, "avatar.svg"));
  await writePack(packRoot, [
    { key: "avatar", source: "local", sourceFile: "avatar.svg", file: "avatars/avatar.webp", objectKey: "seed/content-v2/avatars/avatar.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" },
  ]);
  await mkdir(path.dirname(oldOutput), { recursive: true });
  await writeFile(oldOutput, oldBytes);
  await mkdir(path.dirname(reportDirectory), { recursive: true });
  await mkdir(outside, { recursive: true });
  try {
    await symlink(outside, reportDirectory, process.platform === "win32" ? "junction" : "dir");
  } catch (error) {
    if (["EPERM", "EACCES", "ENOSYS"].includes(error.code)) {
      context.skip(`symlink/junction creation unavailable: ${error.code}`);
      return;
    }
    throw error;
  }

  await assert.rejects(() => preparePack({ packRoot, projectRoot }), /outside the trusted pack/i);

  assert.deepEqual(await readFile(oldOutput), oldBytes);
  await assert.rejects(() => stat(path.join(outside, "media-report.json")), { code: "ENOENT" });
  await assert.rejects(() => stat(path.join(packRoot, ".media-prepare.lock")), { code: "ENOENT" });
});

test("does not create report directories through an escaping project junction", async (context) => {
  const projectRoot = path.join(testRoot, "mkdir-escape-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v2");
  const incomingRoot = path.join(projectRoot, "apps", "web", "public", "images", "_incoming", "pic");
  const codexTemp = path.join(projectRoot, ".codex-tmp");
  const outside = path.join(testRoot, "outside-empty-report-root");
  await writeSvg(path.join(incomingRoot, "avatar.svg"));
  await writePack(packRoot, [
    { key: "avatar", source: "local", sourceFile: "avatar.svg", file: "avatars/avatar.webp", objectKey: "seed/content-v2/avatars/avatar.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" },
  ]);
  await mkdir(outside, { recursive: true });
  try {
    await symlink(outside, codexTemp, process.platform === "win32" ? "junction" : "dir");
  } catch (error) {
    if (["EPERM", "EACCES", "ENOSYS"].includes(error.code)) {
      context.skip(`symlink/junction creation unavailable: ${error.code}`);
      return;
    }
    throw error;
  }

  await assert.rejects(() => preparePack({ packRoot, projectRoot }), /outside the trusted pack/i);

  await assert.rejects(() => stat(path.join(outside, "seed-content-v2")), { code: "ENOENT" });
  await assert.rejects(() => stat(path.join(packRoot, ".media-prepare.lock")), { code: "ENOENT" });
});

test("rejects escaped paths, missing sources, unsupported inputs, oversized files, and duplicate declarations", async () => {
  const projectRoot = path.join(testRoot, "reject-project");
  const packRoot = path.join(projectRoot, "content", "seed", "content-v2");
  const sourcesRoot = path.join(projectRoot, ".codex-tmp", "seed-content-v2", "sources");
  await mkdir(sourcesRoot, { recursive: true });
  await writeFile(path.join(sourcesRoot, "plain.txt"), "not an image");
  await writeFile(path.join(sourcesRoot, "large.png"), Buffer.alloc(15 * 1024 * 1024 + 1));

  const base = { key: "one", source: "local", sourceUrl: "https://example.test/must-not-be-fetched.png", sourceFile: "missing.png", file: "avatars/one.webp", objectKey: "seed/content-v2/avatars/one.webp", sha256: "", contentType: "", width: 0, height: 0, usage: "avatar" };
  for (const [name, assets, expected] of [
    ["missing", [base], /source file not found/i],
    ["source escape", [{ ...base, sourceFile: "../outside.png" }], /sourceFile.*approved/i],
    ["output escape", [{ ...base, sourceFile: "plain.txt", file: "../escape.webp" }], /output.*assets/i],
    ["unsupported", [{ ...base, sourceFile: "plain.txt" }], /unsupported image/i],
    ["oversized", [{ ...base, sourceFile: "large.png" }], /15 MiB/i],
    ["duplicate key", [base, { ...base, file: "avatars/two.webp" }], /duplicate asset key/i],
    ["duplicate output", [base, { ...base, key: "two" }], /duplicate output file/i],
  ]) {
    await writePack(packRoot, assets);
    await assert.rejects(() => preparePack({ packRoot, projectRoot }), expected, name);
  }
});
