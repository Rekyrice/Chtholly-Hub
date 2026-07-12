import { createHash, randomUUID } from "node:crypto";
import { constants as fsConstants } from "node:fs";
import { access, lstat, mkdir, open, realpath, rename, rm } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const MAX_BYTES = 15 * 1024 * 1024;
const MAX_REDIRECTS = 5;
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

async function resolveSafeOutput(output) {
  if (typeof output !== "string" || output.trim() === "") {
    throw new Error("--output must be a nonblank project-local destination");
  }
  const resolvedRepoRoot = await realpath(REPO_ROOT);
  const resolvedOutput = path.resolve(output);
  if (!isInside(REPO_ROOT, resolvedOutput)) {
    throw new Error("--output must resolve inside the repository root");
  }

  const parent = path.dirname(resolvedOutput);
  const ancestor = await nearestExistingAncestor(parent);
  const resolvedAncestor = await realpath(ancestor);
  if (!isInside(resolvedRepoRoot, resolvedAncestor)) {
    throw new Error("--output cannot escape the repository root through a symlink parent");
  }

  await mkdir(parent, { recursive: true });
  const resolvedParent = await realpath(parent);
  if (!isInside(resolvedRepoRoot, resolvedParent)) {
    throw new Error("--output cannot escape the repository root through a symlink parent");
  }

  try {
    const outputStat = await lstat(resolvedOutput);
    if (outputStat.isSymbolicLink()) {
      throw new Error("--output cannot be an existing symlink");
    }
  } catch (error) {
    if (error.code !== "ENOENT") {
      throw error;
    }
  }
  return resolvedOutput;
}

async function fetchWithRedirects(initialUrl, userAgent) {
  let current = initialUrl;
  for (let redirects = 0; redirects <= MAX_REDIRECTS; redirects += 1) {
    const response = await fetch(current, {
      headers: { "User-Agent": userAgent },
      redirect: "manual",
    });
    if (![301, 302, 303, 307, 308].includes(response.status)) {
      return response;
    }

    if (redirects === MAX_REDIRECTS) {
      await response.body?.cancel();
      throw new Error(`Redirect limit exceeded (${MAX_REDIRECTS})`);
    }
    const location = response.headers.get("location");
    if (!location) {
      await response.body?.cancel();
      throw new Error(`Redirect response ${response.status} is missing Location`);
    }
    const redirected = new URL(location, current);
    parseHttpUrl(redirected.href, "redirect URL");
    await response.body?.cancel();
    current = redirected;
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

async function writeResponseAtomically(response, output) {
  const temporary = path.join(
    path.dirname(output),
    `.${path.basename(output)}.${process.pid}.${randomUUID()}.tmp`,
  );
  const hash = createHash("sha256");
  let bytes = 0;
  let handle;

  try {
    handle = await open(temporary, "wx");
    if (!response.body) {
      throw new Error("Image response has no body");
    }
    for await (const chunk of response.body) {
      const buffer = Buffer.from(chunk);
      bytes += buffer.length;
      if (bytes > MAX_BYTES) {
        throw new Error("Image is too large: maximum size is 15 MiB");
      }
      hash.update(buffer);
      let offset = 0;
      while (offset < buffer.length) {
        const { bytesWritten } = await handle.write(buffer, offset);
        if (bytesWritten === 0) {
          throw new Error("Unable to make progress while writing the image");
        }
        offset += bytesWritten;
      }
    }
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(temporary, output);
    return { bytes, sha256: hash.digest("hex") };
  } catch (error) {
    await response.body?.cancel().catch(() => {});
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
}) {
  const sourceUrl = parseHttpUrl(url, "--url").href;
  const sourcePageUrl = parseHttpUrl(sourcePage, "--source-page").href;
  if (typeof userAgent !== "string" || userAgent.trim() === "") {
    throw new Error("--user-agent must be nonblank");
  }
  const normalizedFetchedAt = normalizeFetchedAt(fetchedAt);
  const resolvedOutput = await resolveSafeOutput(output);
  const response = await fetchWithRedirects(sourceUrl, userAgent);

  if (!response.ok) {
    await response.body?.cancel();
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
  const written = await writeResponseAtomically(response, resolvedOutput);

  return {
    sourceUrl: url,
    sourcePageUrl: sourcePage,
    finalUrl: response.url,
    fetchedAt: normalizedFetchedAt,
    sha256: written.sha256,
    contentType,
    bytes: written.bytes,
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
