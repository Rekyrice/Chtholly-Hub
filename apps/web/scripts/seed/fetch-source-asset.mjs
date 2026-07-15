import { createHash, randomUUID } from "node:crypto";
import { constants as fsConstants } from "node:fs";
import { access, lstat, mkdir, open, realpath, rename, rm, stat } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const MAX_BYTES = 15 * 1024 * 1024;
const MAX_REDIRECTS = 5;
const DEFAULT_TIMEOUT_MS = 60_000;
const MAX_TIMEOUT_MS = 10 * 60_000;
const ALLOWED_CONTENT_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
  "image/gif",
]);
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, "../../../..");

function isInside(parent, candidate) {
  const relative = path.relative(parent, candidate);
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

function parseHttpUrl(value, label) {
  let parsed;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`${label} must be a valid HTTP(S) URL`);
  }
  if (!(["http:", "https:"].includes(parsed.protocol)) || !parsed.hostname) {
    throw new Error(`${label} must be a valid HTTP(S) URL with a host`);
  }
  return parsed;
}

function normalizeFetchedAt(value) {
  if (value === undefined) {
    return new Date().toISOString();
  }
  if (typeof value !== "string") {
    throw new Error("--fetched-at must be a valid ISO instant");
  }

  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(\.\d+)?(Z|[+-]\d{2}:\d{2})$/.exec(value);
  if (!match) {
    throw new Error("--fetched-at must be a valid ISO instant");
  }

  const [, year, month, day, hour, minute, second, , zone] = match;
  const calendarProbe = new Date(`${year}-${month}-${day}T${hour}:${minute}:${second}Z`);
  const calendarMatches = !Number.isNaN(calendarProbe.getTime())
    && calendarProbe.getUTCFullYear() === Number(year)
    && calendarProbe.getUTCMonth() + 1 === Number(month)
    && calendarProbe.getUTCDate() === Number(day)
    && calendarProbe.getUTCHours() === Number(hour)
    && calendarProbe.getUTCMinutes() === Number(minute)
    && calendarProbe.getUTCSeconds() === Number(second);
  const zoneMatches = zone === "Z"
    || (Number(zone.slice(1, 3)) <= 23 && Number(zone.slice(4, 6)) <= 59);
  const instant = new Date(value);
  if (!calendarMatches || !zoneMatches || Number.isNaN(instant.getTime())) {
    throw new Error("--fetched-at must be a valid ISO instant");
  }
  return instant.toISOString();
}

async function nearestExistingAncestor(candidate) {
  let current = candidate;
  while (true) {
    try {
      await access(current, fsConstants.F_OK);
      return current;
    } catch (error) {
      if (error.code !== "ENOENT") {
        throw error;
      }
      const parent = path.dirname(current);
      if (parent === current) {
        throw new Error(`No existing ancestor found for output path: ${candidate}`);
      }
      current = parent;
    }
  }
}

function resolveOutputPath(output) {
  if (typeof output !== "string" || output.trim() === "") {
    throw new Error("--output must be a nonblank project-local destination");
  }
  const resolvedOutput = path.resolve(output);
  if (!isInside(REPO_ROOT, resolvedOutput)) {
    throw new Error("--output must resolve inside the repository root");
  }
  return resolvedOutput;
}

function validateTimeoutMs(timeoutMs) {
  if (!Number.isInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > MAX_TIMEOUT_MS) {
    throw new Error(`timeoutMs must be a positive integer no greater than ${MAX_TIMEOUT_MS}`);
  }
  return timeoutMs;
}

function comparablePath(value) {
  return process.platform === "win32" ? value.toLowerCase() : value;
}

async function captureParentIdentity(parent, resolvedRepoRoot) {
  const [resolvedParent, parentStat] = await Promise.all([
    realpath(parent),
    stat(parent, { bigint: true }),
  ]);
  if (!parentStat.isDirectory()) {
    throw new Error("Output parent must be a directory");
  }
  if (!isInside(resolvedRepoRoot, resolvedParent)) {
    throw new Error("--output cannot escape the repository root through a symlink parent");
  }
  return {
    realPath: comparablePath(resolvedParent),
    dev: parentStat.dev,
    ino: parentStat.ino,
    mode: parentStat.mode,
    birthtime: String(parentStat.birthtimeNs ?? parentStat.birthtimeMs),
  };
}

function sameParentIdentity(expected, actual) {
  const inodeAvailable = expected.ino !== 0n && actual.ino !== 0n;
  if (inodeAvailable) {
    return expected.realPath === actual.realPath
      && expected.dev === actual.dev
      && expected.ino === actual.ino;
  }
  return expected.realPath === actual.realPath
    && expected.dev === actual.dev
    && expected.mode === actual.mode
    && expected.birthtime === actual.birthtime;
}

async function assertParentIdentity(parent, expected, resolvedRepoRoot) {
  const actual = await captureParentIdentity(parent, resolvedRepoRoot);
  if (!sameParentIdentity(expected, actual)) {
    throw new Error("Output parent identity changed during atomic write");
  }
}

async function assertOutputIsNotSymlink(output) {
  try {
    const outputStat = await lstat(output);
    if (outputStat.isSymbolicLink()) {
      throw new Error("--output cannot be an existing symlink");
    }
  } catch (error) {
    if (error.code !== "ENOENT") {
      throw error;
    }
  }
}

async function prepareOutputParent(resolvedOutput) {
  const resolvedRepoRoot = await realpath(REPO_ROOT);

  const parent = path.dirname(resolvedOutput);
  const ancestor = await nearestExistingAncestor(parent);
  const resolvedAncestor = await realpath(ancestor);
  if (!isInside(resolvedRepoRoot, resolvedAncestor)) {
    throw new Error("--output cannot escape the repository root through a symlink parent");
  }

  await mkdir(parent, { recursive: true });
  const identity = await captureParentIdentity(parent, resolvedRepoRoot);
  await assertOutputIsNotSymlink(resolvedOutput);
  return { parent, identity, resolvedRepoRoot };
}

async function fetchWithRedirects(initialUrl, userAgent, signal) {
  let current = initialUrl;
  for (let redirects = 0; redirects <= MAX_REDIRECTS; redirects += 1) {
    const response = await fetch(current, {
      headers: { "User-Agent": userAgent },
      redirect: "manual",
      signal,
    });
    if (![301, 302, 303, 307, 308].includes(response.status)) {
      return response;
    }

    let redirected;
    try {
      if (redirects === MAX_REDIRECTS) {
        throw new Error(`Redirect limit exceeded (${MAX_REDIRECTS})`);
      }
      const location = response.headers.get("location");
      if (!location) {
        throw new Error(`Redirect response ${response.status} is missing Location`);
      }
      redirected = new URL(location, current);
      parseHttpUrl(redirected.href, "redirect URL");
    } finally {
      await response.body?.cancel().catch(() => {});
    }
    current = redirected.href;
  }
  throw new Error(`Redirect limit exceeded (${MAX_REDIRECTS})`);
}

function normalizeContentType(response) {
  const contentType = (response.headers.get("content-type") ?? "")
    .split(";", 1)[0]
    .trim()
    .toLowerCase();
  if (!ALLOWED_CONTENT_TYPES.has(contentType)) {
    throw new Error(`Unsupported image Content-Type: ${contentType || "missing"}`);
  }
  return contentType;
}

function validateContentLength(response) {
  const header = response.headers.get("content-length");
  if (header === null) {
    return;
  }
  const contentLength = Number(header);
  if (Number.isFinite(contentLength) && contentLength > MAX_BYTES) {
    throw new Error("Image is too large: maximum size is 15 MiB");
  }
}

async function readResponseIntoMemory(response) {
  if (!response.body) {
    throw new Error("Image response has no body");
  }
  const chunks = [];
  let bytes = 0;
  try {
    for await (const chunk of response.body) {
      const buffer = Buffer.from(chunk);
      bytes += buffer.length;
      if (bytes > MAX_BYTES) {
        throw new Error("Image is too large: maximum size is 15 MiB");
      }
      chunks.push(buffer);
    }
  } catch (error) {
    await response.body.cancel().catch(() => {});
    throw error;
  }
  return Buffer.concat(chunks, bytes);
}

/**
 * Writes a fully downloaded image using repeated parent-directory identity checks.
 *
 * This is a practical TOCTOU mitigation, not an absolute sandbox boundary. Node does
 * not expose the openat/renameat directory-handle operations needed to guarantee
 * resistance against a local writer continuously swapping paths at nanosecond scale.
 */
async function writeBufferAtomically(buffer, output) {
  const { parent, identity, resolvedRepoRoot } = await prepareOutputParent(output);
  const temporary = path.join(
    parent,
    `.${path.basename(output)}.${process.pid}.${randomUUID()}.tmp`,
  );
  let handle;

  try {
    await assertParentIdentity(parent, identity, resolvedRepoRoot);
    const noFollow = fsConstants.O_NOFOLLOW ?? 0;
    const flags = fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_WRONLY | noFollow;
    handle = await open(temporary, flags, 0o600);
    await assertParentIdentity(parent, identity, resolvedRepoRoot);
    let offset = 0;
    while (offset < buffer.length) {
      const { bytesWritten } = await handle.write(buffer, offset);
      if (bytesWritten === 0) {
        throw new Error("Unable to make progress while writing the image");
      }
      offset += bytesWritten;
    }
    await handle.sync();
    await handle.close();
    handle = undefined;
    await assertParentIdentity(parent, identity, resolvedRepoRoot);
    await assertOutputIsNotSymlink(output);
    await assertParentIdentity(parent, identity, resolvedRepoRoot);
    await rename(temporary, output);
  } catch (error) {
    if (handle) {
      await handle.close().catch(() => {});
    }
    await rm(temporary, { force: true }).catch(() => {});
    throw error;
  }
}

export async function fetchSourceAsset({
  url,
  sourcePage,
  output,
  userAgent,
  fetchedAt,
  timeoutMs = DEFAULT_TIMEOUT_MS,
}) {
  const normalizedTimeoutMs = validateTimeoutMs(timeoutMs);
  const sourceUrl = parseHttpUrl(url, "--url").href;
  const sourcePageUrl = parseHttpUrl(sourcePage, "--source-page").href;
  if (typeof userAgent !== "string" || userAgent.trim() === "") {
    throw new Error("--user-agent must be nonblank");
  }
  const normalizedFetchedAt = normalizeFetchedAt(fetchedAt);
  const resolvedOutput = resolveOutputPath(output);
  const controller = new AbortController();
  const timeoutError = new Error(`Source image fetch timed out after ${normalizedTimeoutMs} ms`);
  const timer = setTimeout(() => controller.abort(timeoutError), normalizedTimeoutMs);
  let downloaded;
  try {
    const response = await fetchWithRedirects(sourceUrl, userAgent, controller.signal);
    if (!response.ok) {
      await response.body?.cancel().catch(() => {});
      throw new Error(`Image request returned non-2xx status ${response.status}`);
    }
    parseHttpUrl(response.url, "final response URL");
    let contentType;
    try {
      contentType = normalizeContentType(response);
      validateContentLength(response);
    } catch (error) {
      await response.body?.cancel().catch(() => {});
      throw error;
    }
    const buffer = await readResponseIntoMemory(response);
    downloaded = { buffer, contentType, finalUrl: response.url };
  } catch (error) {
    if (controller.signal.aborted) {
      throw timeoutError;
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }

  await writeBufferAtomically(downloaded.buffer, resolvedOutput);

  return {
    sourceUrl: url,
    sourcePageUrl: sourcePage,
    finalUrl: downloaded.finalUrl,
    fetchedAt: normalizedFetchedAt,
    sha256: createHash("sha256").update(downloaded.buffer).digest("hex"),
    contentType: downloaded.contentType,
    bytes: downloaded.buffer.length,
  };
}

export function parseArguments(argv) {
  const values = {};
  const names = new Map([
    ["--url", "url"],
    ["--source-page", "sourcePage"],
    ["--output", "output"],
    ["--user-agent", "userAgent"],
    ["--fetched-at", "fetchedAt"],
  ]);
  for (let index = 0; index < argv.length; index += 2) {
    const option = argv[index];
    const key = names.get(option);
    const value = argv[index + 1];
    if (!key || value === undefined || value.startsWith("--")) {
      throw new Error(`Invalid or incomplete argument: ${option ?? "(missing)"}`);
    }
    if (Object.hasOwn(values, key)) {
      throw new Error(`Duplicate argument: ${option}`);
    }
    values[key] = value;
  }
  for (const [option, key] of names) {
    if (option !== "--fetched-at" && !Object.hasOwn(values, key)) {
      throw new Error(`Missing required argument: ${option}`);
    }
  }
  return values;
}

function isMain() {
  if (!process.argv[1]) {
    return false;
  }
  const invoked = pathToFileURL(path.resolve(process.argv[1])).href;
  return process.platform === "win32"
    ? invoked.toLowerCase() === import.meta.url.toLowerCase()
    : invoked === import.meta.url;
}

if (isMain()) {
  try {
    const metadata = await fetchSourceAsset(parseArguments(process.argv.slice(2)));
    process.stdout.write(`${JSON.stringify(metadata)}\n`);
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : String(error)}\n`);
    process.exitCode = 1;
  }
}
