import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { createServer } from "node:http";
import { mkdir, mkdtemp, readFile, readdir, rm, symlink } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

import { fetchSourceAsset } from "./fetch-source-asset.mjs";

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, "../../../..");
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
