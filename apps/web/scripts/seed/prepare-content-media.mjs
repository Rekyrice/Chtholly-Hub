import { createHash, randomUUID } from "node:crypto";
import { access, mkdir, mkdtemp, readFile, realpath, rename, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";

import sharp from "sharp";
import YAML from "yaml";

const MAX_SOURCE_BYTES = 15 * 1024 * 1024;
const SAFE_PACK_VERSION = /^content-v[1-9][0-9]*$/;
const SUPPORTED_INPUT_FORMATS = new Set(["avif", "gif", "heif", "jpeg", "png", "svg", "tiff", "webp"]);
const WEBP_QUALITY = Object.freeze({ avatar: 82, cover: 84, inline: 85 });
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const defaultProjectRoot = path.resolve(scriptDirectory, "../../../..");

function sha256(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

function isWithin(root, target) {
  const relative = path.relative(root, target);
  return relative === "" || (!relative.startsWith(`..${path.sep}`) && relative !== ".." && !path.isAbsolute(relative));
}

function comparisonPath(file) {
  const normalized = path.normalize(path.resolve(file));
  return process.platform === "win32" ? normalized.toLowerCase() : normalized;
}

async function exists(file) {
  try {
    await access(file);
    return true;
  } catch {
    return false;
  }
}

async function inspectInput(input) {
  const sourceStat = await stat(input);
  if (!sourceStat.isFile()) {
    throw new Error(`Source is not a file: ${input}`);
  }
  if (sourceStat.size > MAX_SOURCE_BYTES) {
    throw new Error(`Source file exceeds 15 MiB: ${input}`);
  }

  let metadata;
  try {
    metadata = await sharp(input, { animated: false, failOn: "error" }).metadata();
  } catch (error) {
    throw new Error(`Unsupported image input: ${input}`, { cause: error });
  }
  if (!metadata.format || !SUPPORTED_INPUT_FORMATS.has(metadata.format)) {
    throw new Error(`Unsupported image input: ${input}`);
  }
}

async function renderOne({ input, width, height, fit = "inside", withoutEnlargement = false, quality = 85 }) {
  await inspectInput(input);
  const { data, info } = await sharp(input, { animated: false, failOn: "error" })
    .rotate()
    .resize({ width, height, fit, withoutEnlargement })
    .webp({ quality, alphaQuality: 100, effort: 6, smartSubsample: true })
    .toBuffer({ resolveWithObject: true });
  return {
    data,
    sha256: sha256(data),
    contentType: "image/webp",
    width: info.width,
    height: info.height,
    bytes: data.length,
  };
}

/**
 * Writes one deterministic WebP to an explicitly supplied authoring target.
 *
 * This low-level API intentionally does not enforce content-pack path boundaries. Production
 * CLI flows must use {@link preparePack}, which owns realpath validation, locking and rollback.
 */
export async function prepareOne(options) {
  if (typeof options.output !== "string" || options.output.trim() === "") {
    throw new Error("prepareOne requires an explicit output path");
  }
  const rendered = await renderOne(options);
  const output = path.resolve(options.output);
  const outputDirectory = path.dirname(output);
  const temporary = path.join(outputDirectory, `.${path.basename(output)}.${process.pid}.${randomUUID()}.tmp`);
  await mkdir(outputDirectory, { recursive: true });
  try {
    await writeFile(temporary, rendered.data, { flag: "wx" });
    await rename(temporary, output);
  } finally {
    await rm(temporary, { force: true });
  }
  return {
    sha256: rendered.sha256,
    contentType: rendered.contentType,
    width: rendered.width,
    height: rendered.height,
    bytes: rendered.bytes,
  };
}

async function resolveApprovedSource(projectRoot, version, sourceFile) {
  if (typeof sourceFile !== "string" || sourceFile.trim() === "") {
    throw new Error("sourceFile must be a non-empty path");
  }

  const approvedRoots = [
    path.join(projectRoot, ".codex-tmp", `seed-${version}`, "sources"),
    path.join(projectRoot, "apps", "web", "public", "images", "_incoming", "pic"),
  ].map((root) => path.resolve(root));

  let candidates;
  if (path.isAbsolute(sourceFile)) {
    candidates = [path.resolve(sourceFile)];
  } else {
    const normalized = path.normalize(sourceFile);
    if (normalized === ".." || normalized.startsWith(`..${path.sep}`)) {
      throw new Error(`sourceFile must stay within an approved source directory: ${sourceFile}`);
    }
    candidates = approvedRoots.map((root) => path.resolve(root, normalized));
  }

  for (const candidate of candidates) {
    const lexicalRoot = approvedRoots.find((root) => isWithin(root, candidate));
    if (!lexicalRoot || !(await exists(candidate))) {
      continue;
    }
    const [resolvedRoot, resolvedCandidate, resolvedProject] = await Promise.all([
      realpath(lexicalRoot),
      realpath(candidate),
      realpath(projectRoot),
    ]);
    if (!isWithin(resolvedProject, resolvedRoot) || !isWithin(resolvedRoot, resolvedCandidate)) {
      throw new Error(`sourceFile must stay within an approved source directory: ${sourceFile}`);
    }
    return resolvedCandidate;
  }

  if (path.isAbsolute(sourceFile) && !approvedRoots.some((root) => isWithin(root, path.resolve(sourceFile)))) {
    throw new Error(`sourceFile must stay within an approved source directory: ${sourceFile}`);
  }
  throw new Error(`Source file not found in approved directories: ${sourceFile}`);
}

function resolveOutput(packRoot, declaredFile) {
  if (typeof declaredFile !== "string" || declaredFile.trim() === "" || path.isAbsolute(declaredFile)) {
    throw new Error(`Output file must be relative to the pack assets directory: ${declaredFile}`);
  }
  const assetsRoot = path.resolve(packRoot, "assets");
  const output = path.resolve(assetsRoot, declaredFile);
  if (!isWithin(assetsRoot, output) || path.extname(output).toLowerCase() !== ".webp") {
    throw new Error(`Output file must stay under pack assets and use .webp: ${declaredFile}`);
  }
  return output;
}

function transformForUsage(usage) {
  switch (usage) {
    case "avatar":
      return { width: 512, height: 512, fit: "cover", quality: WEBP_QUALITY.avatar };
    case "cover":
      return { width: 1200, height: 675, fit: "cover", quality: WEBP_QUALITY.cover };
    case "inline":
      return { width: 1600, fit: "inside", withoutEnlargement: true, quality: WEBP_QUALITY.inline };
    default:
      throw new Error(`Unsupported asset usage: ${usage}`);
  }
}

function validateDeclarations(assets, packRoot) {
  if (!Array.isArray(assets)) {
    throw new Error("assets.yml must contain a YAML sequence");
  }
  const keys = new Set();
  const outputs = new Set();
  return assets.map((asset) => {
    if (!asset || typeof asset !== "object") {
      throw new Error("Every assets.yml entry must be a mapping");
    }
    if (typeof asset.key !== "string" || asset.key.trim() === "") {
      throw new Error("Every asset must declare a non-empty key");
    }
    if (keys.has(asset.key)) {
      throw new Error(`Duplicate asset key: ${asset.key}`);
    }
    keys.add(asset.key);
    const output = resolveOutput(packRoot, asset.file);
    const normalizedOutput = comparisonPath(output);
    if (outputs.has(normalizedOutput)) {
      throw new Error(`Duplicate output file after path normalization: ${asset.file}`);
    }
    outputs.add(normalizedOutput);
    return { asset, output };
  });
}

async function findExistingAncestor(file) {
  let current = path.resolve(file);
  while (!(await exists(current))) {
    const parent = path.dirname(current);
    if (parent === current) {
      throw new Error(`No existing ancestor for path: ${file}`);
    }
    current = parent;
  }
  return current;
}

async function assertTrustedPath(file, trustedRoot, label) {
  const existingAncestor = await findExistingAncestor(file);
  const resolvedAncestor = await realpath(existingAncestor);
  if (!isWithin(trustedRoot, resolvedAncestor)) {
    throw new Error(`${label} resolves outside the trusted pack: ${file}`);
  }
}

async function mkdirTrusted(directory, trustedRoot, label) {
  // Validate the closest existing ancestor before mkdir so a junction cannot redirect
  // recursive creation outside the project. Revalidate the final directory to narrow TOCTOU.
  await assertTrustedPath(directory, trustedRoot, label);
  await mkdir(directory, { recursive: true });
  await assertTrustedPath(directory, trustedRoot, label);
}

async function resolveTrustedPack(projectRoot, packRoot) {
  const [resolvedProject, resolvedPack] = await Promise.all([realpath(projectRoot), realpath(packRoot)]);
  const trustedContentRoot = path.join(resolvedProject, "content", "seed");
  if (!isWithin(trustedContentRoot, resolvedPack)) {
    throw new Error(`Pack resolves outside the trusted content/seed directory: ${packRoot}`);
  }
  const assetsFile = path.join(packRoot, "assets.yml");
  const manifestFile = path.join(packRoot, "manifest.yml");
  const [resolvedAssetsFile, resolvedManifestFile] = await Promise.all([
    realpath(assetsFile),
    realpath(manifestFile),
  ]);
  if (!isWithin(resolvedPack, resolvedAssetsFile) || !isWithin(resolvedPack, resolvedManifestFile)) {
    throw new Error(`assets.yml resolves outside the trusted pack: ${assetsFile}`);
  }
  return { resolvedPack, resolvedProject, assetsFile, manifestFile };
}

async function readPackVersion(manifestFile) {
  const manifest = YAML.parse(await readFile(manifestFile, "utf8"));
  const version = manifest?.version;
  if (typeof version !== "string" || !SAFE_PACK_VERSION.test(version)) {
    throw new Error(`Unsafe content pack version: ${version}`);
  }
  return version;
}

async function assertOutputMatches(output, expected, asset) {
  if (!(await exists(output))) {
    throw new Error(`Prepared output is missing for ${asset.key}: ${asset.file}`);
  }
  const actual = await readFile(output);
  const actualHash = sha256(actual);
  if (!actual.equals(expected.data)) {
    throw new Error(`Prepared output differs from deterministic render for ${asset.key}`);
  }
  if (asset.sha256 !== actualHash) {
    throw new Error(`sha256 mismatch for ${asset.key}: declared ${asset.sha256 || "<empty>"}, actual ${actualHash}`);
  }
  if (asset.contentType !== expected.contentType || asset.width !== expected.width || asset.height !== expected.height) {
    throw new Error(`Media metadata mismatch for ${asset.key}`);
  }
}

async function acquireWriterLock(lockDirectory, trustedRoot) {
  await assertTrustedPath(lockDirectory, trustedRoot, "Media preparation lock");
  let created = false;
  try {
    await mkdir(lockDirectory);
    created = true;
    await assertTrustedPath(lockDirectory, trustedRoot, "Media preparation lock");
  } catch (error) {
    if (created) {
      await rm(lockDirectory, { recursive: true, force: true });
    }
    if (error.code === "EEXIST") {
      throw new Error(`Content pack is already being prepared: ${lockDirectory}`, { cause: error });
    }
    throw error;
  }
}

async function replaceTransaction(targets, stagingRoot) {
  const installed = [];
  const resolvedStaging = await realpath(stagingRoot);
  try {
    for (let index = 0; index < targets.length; index += 1) {
      const target = targets[index];
      await mkdirTrusted(path.dirname(target.final), target.trustedRoot, `${target.label} parent`);
      await assertTrustedPath(target.staged, resolvedStaging, `${target.label} staged file`);
      const backup = path.join(stagingRoot, "backups", `${index}.bak`);
      const hadOriginal = await exists(target.final);
      if (hadOriginal) {
        await mkdirTrusted(path.dirname(backup), resolvedStaging, "Media backup directory");
        await rename(target.final, backup);
      }
      try {
        await rename(target.staged, target.final);
      } catch (error) {
        if (hadOriginal) {
          await rename(backup, target.final);
        }
        throw error;
      }
      installed.push({ final: target.final, backup, hadOriginal });
    }
  } catch (error) {
    for (const item of installed.reverse()) {
      await rm(item.final, { force: true });
      if (item.hadOriginal) {
        await rename(item.backup, item.final);
      }
    }
    throw error;
  }
}

function reportEntry(asset, rendered) {
  return {
    key: asset.key,
    usage: asset.usage,
    sourceFile: asset.sourceFile,
    sourceUrl: asset.sourceUrl ?? null,
    file: asset.file.replaceAll("\\", "/"),
    objectKey: asset.objectKey,
    sha256: rendered.sha256,
    contentType: rendered.contentType,
    width: rendered.width,
    height: rendered.height,
    bytes: rendered.bytes,
  };
}

/**
 * Prepares or verifies every declaration in one versioned content pack.
 */
export async function preparePack({ packRoot, projectRoot = defaultProjectRoot, check = false, writeHashes = false }) {
  if (check && writeHashes) {
    throw new Error("--check and --write-hashes are mutually exclusive");
  }
  const normalizedProjectRoot = path.resolve(projectRoot);
  const normalizedPackRoot = path.resolve(packRoot);
  const { resolvedPack, resolvedProject, assetsFile, manifestFile } = await resolveTrustedPack(
    normalizedProjectRoot,
    normalizedPackRoot,
  );
  const version = await readPackVersion(manifestFile);
  const assets = YAML.parse(await readFile(assetsFile, "utf8"));
  const declarations = validateDeclarations(assets, normalizedPackRoot)
    .sort((left, right) => left.asset.key.localeCompare(right.asset.key, "en"));
  for (const declaration of declarations) {
    await assertTrustedPath(declaration.output, resolvedPack, `Output for ${declaration.asset.key}`);
  }

  const lockDirectory = path.join(normalizedPackRoot, ".media-prepare.lock");
  if (check && (await exists(lockDirectory))) {
    throw new Error(`Content pack is already being prepared: ${lockDirectory}`);
  }

  let lockAcquired = false;
  let stagingRoot;
  try {
    if (!check) {
      await acquireWriterLock(lockDirectory, resolvedPack);
      lockAcquired = true;
      await assertTrustedPath(normalizedPackRoot, resolvedPack, "Content pack staging parent");
      stagingRoot = await mkdtemp(path.join(normalizedPackRoot, ".media-staging-"));
      await assertTrustedPath(stagingRoot, resolvedPack, "Media staging directory");
    }

    const prepared = [];
    const updates = new Map();
    const targets = [];
    for (let index = 0; index < declarations.length; index += 1) {
      const { asset, output } = declarations[index];
      const input = await resolveApprovedSource(normalizedProjectRoot, version, asset.sourceFile);
      const rendered = await renderOne({ input, ...transformForUsage(asset.usage) });
      if (check) {
        await assertOutputMatches(output, rendered, asset);
      } else {
        const staged = path.join(stagingRoot, "assets", `${index}.webp`);
        const resolvedStaging = await realpath(stagingRoot);
        await mkdirTrusted(path.dirname(staged), resolvedStaging, "Media staging asset directory");
        await writeFile(staged, rendered.data);
        targets.push({ staged, final: output, trustedRoot: resolvedPack, label: `Output for ${asset.key}` });
      }

      updates.set(asset.key, {
        ...asset,
        sha256: rendered.sha256,
        contentType: rendered.contentType,
        width: rendered.width,
        height: rendered.height,
      });
      prepared.push(reportEntry(asset, rendered));
    }

    const report = { version: 1, assets: prepared };
    if (check) {
      return report;
    }

    if (writeHashes) {
      const stagedYaml = path.join(stagingRoot, "assets.yml");
      await writeFile(stagedYaml, YAML.stringify(assets.map((asset) => updates.get(asset.key)), { lineWidth: 0 }));
      targets.push({ staged: stagedYaml, final: assetsFile, trustedRoot: resolvedPack, label: "assets.yml" });
    }

    const reportFile = path.join(normalizedProjectRoot, ".codex-tmp", `seed-${version}`, "media-report.json");
    const stagedReport = path.join(stagingRoot, "media-report.json");
    await writeFile(stagedReport, `${JSON.stringify(report, null, 2)}\n`);
    targets.push({ staged: stagedReport, final: reportFile, trustedRoot: resolvedProject, label: "Media report" });

    await replaceTransaction(targets, stagingRoot);
    return report;
  } finally {
    try {
      if (stagingRoot) {
        await rm(stagingRoot, { recursive: true, force: true });
      }
    } finally {
      if (lockAcquired) {
        await rm(lockDirectory, { recursive: true, force: true });
      }
    }
  }
}

function parseArguments(argv) {
  let pack;
  let check = false;
  let writeHashes = false;
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--pack") {
      pack = argv[++index];
    } else if (argument === "--check") {
      check = true;
    } else if (argument === "--write-hashes") {
      writeHashes = true;
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }
  }
  if (!pack) {
    throw new Error("Usage: prepare-content-media.mjs --pack <content-pack> [--check | --write-hashes]");
  }
  return { pack, check, writeHashes };
}

async function resolveCliPack(packArgument) {
  const direct = path.resolve(process.cwd(), packArgument);
  if (await exists(direct)) {
    return direct;
  }
  const segments = packArgument.replaceAll("\\", "/").split("/");
  const contentIndex = segments.lastIndexOf("content");
  if (contentIndex >= 0) {
    return path.resolve(defaultProjectRoot, ...segments.slice(contentIndex));
  }
  return direct;
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  const packRoot = await resolveCliPack(args.pack);
  const result = await preparePack({ ...args, packRoot });
  const output = args.check
    ? JSON.stringify(result, null, 2)
    : JSON.stringify({ assets: result.assets.length, mode: args.writeHashes ? "write-hashes" : "prepare" });
  process.stdout.write(`${output}\n`);
}

const isEntryPoint = process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;
if (isEntryPoint) {
  main().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
