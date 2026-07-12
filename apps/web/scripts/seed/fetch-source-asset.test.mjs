import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { mkdir, mkdtemp, readFile, readdir, rm, symlink } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

import { fetchSourceAsset } from "./fetch-source-asset.mjs";

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, "../../../..");
const CLI_PATH = path.join(SCRIPT_DIR, "fetch-source-asset.mjs");
const TEST_ROOT = path.join(REPO_ROOT, ".codex-tmp", "fetch-source-asset-tests");
const PNG_BYTES = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
  0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
]);

async function withServer(handler, run) {
  const server = createServer(handler);
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });

  const address = server.address();
  const origin = `http://127.0.0.1:${address.port}`;
  try {
    return await run(origin);
  } finally {
    await new Promise((resolve, reject) => {
      server.close((error) => (error ? reject(error) : resolve()));
    });
  }
}

async function makeCaseDir(t) {
  await mkdir(TEST_ROOT, { recursive: true });
  const directory = await mkdtemp(path.join(TEST_ROOT, "case-"));
  t.after(() => rm(directory, { recursive: true, force: true }));
  return directory;
}

function options(origin, output, overrides = {}) {
  return {
    url: `${origin}/image`,
    sourcePage: `${origin}/post/1`,
    output,
    userAgent: "Chtholly-Hub seed source fetcher/1.0 (contact: maintainer@example.test)",
    fetchedAt: "2026-07-12T03:04:05.000Z",
    ...overrides,
  };
}

function cliArguments(origin, output, overrides = {}) {
  const values = {
    url: `${origin}/image`,
    sourcePage: `${origin}/post/1`,
    output,
    userAgent: "Chtholly-Hub CLI integration test/1.0",
    fetchedAt: "2026-07-12T03:04:05.000Z",
    ...overrides,
  };
  const args = [
    "--url", values.url,
    "--source-page", values.sourcePage,
    "--output", values.output,
    "--user-agent", values.userAgent,
  ];
  if (values.fetchedAt !== undefined) {
    args.push("--fetched-at", values.fetchedAt);
  }
  return args;
}

async function runCli(args) {
  const child = spawn(process.execPath, [CLI_PATH, ...args], {
    cwd: REPO_ROOT,
    stdio: ["ignore", "pipe", "pipe"],
    windowsHide: true,
  });
  const stdout = [];
  const stderr = [];
  child.stdout.on("data", (chunk) => stdout.push(chunk));
  child.stderr.on("data", (chunk) => stderr.push(chunk));

  const exitCode = await new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("close", resolve);
  });
  return {
    exitCode,
    stdout: Buffer.concat(stdout).toString("utf8"),
    stderr: Buffer.concat(stderr).toString("utf8"),
  };
}

test.after(async () => {
  await rm(TEST_ROOT, { recursive: true, force: true });
});

test("follows a redirect, sends the custom UA, preserves bytes, and returns deterministic provenance", async (t) => {
  const directory = await makeCaseDir(t);
  const output = path.join(directory, "nested", "source.png");
  const expectedAgent = "Chtholly-Hub source pinning test/1.0";

  await withServer((request, response) => {
    if (request.url === "/image") {
      response.writeHead(302, { Location: "/cdn/final.png" });
      response.end();
      return;
    }
    if (request.url === "/cdn/final.png" && request.headers["user-agent"] === expectedAgent) {
      response.writeHead(200, { "Content-Type": "IMAGE/PNG; charset=binary" });
      response.end(PNG_BYTES);
      return;
    }
    response.writeHead(400);
    response.end();
  }, async (origin) => {
    const metadata = await fetchSourceAsset(options(origin, output, { userAgent: expectedAgent }));

    assert.deepEqual(metadata, {
      sourceUrl: `${origin}/image`,
      sourcePageUrl: `${origin}/post/1`,
      finalUrl: `${origin}/cdn/final.png`,
      fetchedAt: "2026-07-12T03:04:05.000Z",
      sha256: createHash("sha256").update(PNG_BYTES).digest("hex"),
      contentType: "image/png",
      bytes: PNG_BYTES.length,
    });
    assert.deepEqual(await readFile(output), PNG_BYTES);
  });
});

test("accepts an output relative to the caller cwd when it resolves inside the repository", async (t) => {
  const directory = await makeCaseDir(t);
  const relativeOutput = path.relative(process.cwd(), path.join(directory, "source.jpg"));
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xd9]);

  await withServer((_request, response) => {
    response.writeHead(200, { "Content-Type": "image/jpeg" });
    response.end(jpeg);
  }, async (origin) => {
    const metadata = await fetchSourceAsset(options(origin, relativeOutput));
    assert.equal(metadata.contentType, "image/jpeg");
    assert.deepEqual(await readFile(path.join(directory, "source.jpg")), jpeg);
  });
});

test("rejects output paths outside the derived repository root", async (t) => {
  const directory = await makeCaseDir(t);
  const outside = path.resolve(REPO_ROOT, "..", `task17-outside-${process.pid}.png`);
  t.after(() => rm(outside, { force: true }));

  await assert.rejects(
    fetchSourceAsset(options("http://127.0.0.1:1", outside)),
    /inside|repository|repo root/i,
  );
  assert.deepEqual(await readdir(directory), []);
});

test("rejects escape through an existing symlink parent", async (t) => {
  const directory = await makeCaseDir(t);
  const outside = path.resolve(REPO_ROOT, "..", `task17-symlink-target-${process.pid}`);
  const link = path.join(directory, "linked-parent");
  await mkdir(outside, { recursive: true });
  t.after(() => rm(outside, { recursive: true, force: true }));

  try {
    await symlink(outside, link, process.platform === "win32" ? "junction" : "dir");
  } catch (error) {
    if (process.platform === "win32" && ["EPERM", "EACCES", "UNKNOWN"].includes(error.code)) {
      t.skip(`symlink creation unavailable on this Windows host: ${error.code}`);
      return;
    }
    throw error;
  }

  await assert.rejects(
    fetchSourceAsset(options("http://127.0.0.1:1", path.join(link, "escaped.png"))),
    /symlink|inside|repository|repo root/i,
  );
});

test("rejects a successful non-image response and leaves no output or temp file", async (t) => {
  const directory = await makeCaseDir(t);
  await withServer((_request, response) => {
    response.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("not an image");
  }, async (origin) => {
    await assert.rejects(fetchSourceAsset(options(origin, path.join(directory, "source.png"))), /content[- ]type|image/i);
  });
  assert.deepEqual(await readdir(directory), []);
});

test("pre-rejects Content-Length above 15 MiB without leaving partial files", async (t) => {
  const directory = await makeCaseDir(t);
  await withServer((_request, response) => {
    response.writeHead(200, {
      "Content-Type": "image/png",
      "Content-Length": String(15 * 1024 * 1024 + 1),
    });
    response.end();
  }, async (origin) => {
    await assert.rejects(fetchSourceAsset(options(origin, path.join(directory, "source.png"))), /15 MiB|too large|size/i);
  });
  assert.deepEqual(await readdir(directory), []);
});

test("aborts a stream without Content-Length once actual bytes exceed 15 MiB", async (t) => {
  const directory = await makeCaseDir(t);
  const chunk = Buffer.alloc(1024 * 1024, 0xab);
  await withServer((_request, response) => {
    response.writeHead(200, { "Content-Type": "image/webp" });
    for (let index = 0; index < 16; index += 1) {
      response.write(chunk);
    }
    response.end(Buffer.from([0x01]));
  }, async (origin) => {
    await assert.rejects(fetchSourceAsset(options(origin, path.join(directory, "source.webp"))), /15 MiB|too large|size/i);
  });
  assert.deepEqual(await readdir(directory), []);
});

test("rejects a final non-2xx HTTP response", async (t) => {
  const directory = await makeCaseDir(t);
  await withServer((_request, response) => {
    response.writeHead(404, { "Content-Type": "image/gif" });
    response.end("missing");
  }, async (origin) => {
    await assert.rejects(fetchSourceAsset(options(origin, path.join(directory, "source.gif"))), /404|2xx|status/i);
  });
  assert.deepEqual(await readdir(directory), []);
});

test("rejects invalid image and source-page URLs", async (t) => {
  const directory = await makeCaseDir(t);
  const output = path.join(directory, "source.png");
  const base = options("http://127.0.0.1:1", output);

  await assert.rejects(fetchSourceAsset({ ...base, url: "file:///etc/passwd" }), /url|http/i);
  await assert.rejects(fetchSourceAsset({ ...base, url: "https://" }), /url|host/i);
  await assert.rejects(fetchSourceAsset({ ...base, sourcePage: "ftp://example.test/post" }), /source|http/i);
  await assert.rejects(fetchSourceAsset({ ...base, sourcePage: "not a url" }), /source|url/i);
});

test("rejects blank user agents and invalid fetched-at values", async (t) => {
  const directory = await makeCaseDir(t);
  const output = path.join(directory, "source.png");
  const base = options("http://127.0.0.1:1", output);

  await assert.rejects(fetchSourceAsset({ ...base, userAgent: "   " }), /user[- ]agent|blank/i);
  await assert.rejects(fetchSourceAsset({ ...base, fetchedAt: "2026-07-12" }), /fetched-at|ISO|instant/i);
  await assert.rejects(fetchSourceAsset({ ...base, fetchedAt: "definitely-not-a-time" }), /fetched-at|ISO|instant/i);
});

test("rejects redirect loops beyond the configured redirect limit", async (t) => {
  const directory = await makeCaseDir(t);
  await withServer((request, response) => {
    const step = Number(new URL(request.url, "http://localhost").searchParams.get("step") ?? 0);
    response.writeHead(302, { Location: `/image?step=${step + 1}` });
    response.end();
  }, async (origin) => {
    await assert.rejects(fetchSourceAsset(options(origin, path.join(directory, "source.png"))), /redirect|limit|too many/i);
  });
  assert.deepEqual(await readdir(directory), []);
});

test("CLI emits exactly one strict provenance JSON object and writes original bytes", async (t) => {
  const directory = await makeCaseDir(t);
  const output = path.join(directory, "cli-source.png");

  await withServer((_request, response) => {
    response.writeHead(200, { "Content-Type": "image/png" });
    response.end(PNG_BYTES);
  }, async (origin) => {
    const result = await runCli(cliArguments(origin, output));

    assert.equal(result.exitCode, 0);
    assert.equal(result.stderr, "");
    const lines = result.stdout.trim().split(/\r?\n/);
    assert.equal(lines.length, 1);
    const metadata = JSON.parse(lines[0]);
    assert.deepEqual(Object.keys(metadata), [
      "sourceUrl",
      "sourcePageUrl",
      "finalUrl",
      "fetchedAt",
      "sha256",
      "contentType",
      "bytes",
    ]);
    assert.deepEqual(metadata, {
      sourceUrl: `${origin}/image`,
      sourcePageUrl: `${origin}/post/1`,
      finalUrl: `${origin}/image`,
      fetchedAt: "2026-07-12T03:04:05.000Z",
      sha256: createHash("sha256").update(PNG_BYTES).digest("hex"),
      contentType: "image/png",
      bytes: PNG_BYTES.length,
    });
    assert.deepEqual(await readFile(output), PNG_BYTES);
  });
});

test("CLI permits omitted fetched-at and records an ISO instant in the execution window", async (t) => {
  const directory = await makeCaseDir(t);
  const output = path.join(directory, "cli-current-time.png");

  await withServer((_request, response) => {
    response.writeHead(200, { "Content-Type": "image/png" });
    response.end(PNG_BYTES);
  }, async (origin) => {
    const startedAt = Date.now();
    const result = await runCli(cliArguments(origin, output, { fetchedAt: undefined }));
    const completedAt = Date.now();

    assert.equal(result.exitCode, 0);
    assert.equal(result.stderr, "");
    const metadata = JSON.parse(result.stdout.trim());
    assert.match(metadata.fetchedAt, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    const fetchedAt = Date.parse(metadata.fetchedAt);
    assert.ok(fetchedAt >= startedAt, `${metadata.fetchedAt} predates CLI start`);
    assert.ok(fetchedAt <= completedAt, `${metadata.fetchedAt} follows CLI completion`);
  });
});

test("CLI rejects missing required arguments, blank UA, and unknown options without stdout or files", async (t) => {
  const directory = await makeCaseDir(t);
  const base = cliArguments("http://127.0.0.1:1", path.join(directory, "failure.png"));
  const invalidCases = [
    ["missing --url", base.filter((_, index) => ![0, 1].includes(index))],
    ["missing --source-page", base.filter((_, index) => ![2, 3].includes(index))],
    ["missing --output", base.filter((_, index) => ![4, 5].includes(index))],
    ["missing --user-agent", base.filter((_, index) => ![6, 7].includes(index))],
    ["blank --user-agent", base.map((value, index) => (index === 7 ? "   " : value))],
    ["unknown option", [...base, "--surprise", "value"]],
  ];

  for (const [label, args] of invalidCases) {
    const result = await runCli(args);
    assert.notEqual(result.exitCode, 0, label);
    assert.equal(result.stdout, "", label);
    assert.match(result.stderr, /argument|missing|required|user-agent|nonblank|invalid/i, label);
  }
  assert.deepEqual(await readdir(directory), []);
});

test("CLI reports an HTTP image-fetch failure on stderr and leaves no partial or temp file", async (t) => {
  const directory = await makeCaseDir(t);
  const output = path.join(directory, "not-image.png");

  await withServer((_request, response) => {
    response.writeHead(200, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("not an image");
  }, async (origin) => {
    const result = await runCli(cliArguments(origin, output));
    assert.notEqual(result.exitCode, 0);
    assert.equal(result.stdout, "");
    assert.match(result.stderr, /content[- ]type|image/i);
  });
  assert.deepEqual(await readdir(directory), []);
});
