/**
 * 上传 Phase A 种子 Markdown 到 OSS（覆盖 post/*.md）
 * 用法：node scripts/oss/upload-seed-markdown.mjs
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import OSS from "ali-oss";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, "../..");
const seedDir = path.join(__dirname, "seed");

function loadEnv() {
  const envPath = path.join(root, ".env");
  const text = fs.readFileSync(envPath, "utf-8");
  const env = {};
  for (const line of text.split(/\r?\n/)) {
    const t = line.trim();
    if (!t || t.startsWith("#")) continue;
    const i = t.indexOf("=");
    if (i <= 0) continue;
    env[t.slice(0, i).trim()] = t.slice(i + 1).trim();
  }
  return env;
}

const env = loadEnv();
const endpoint = env.OSS_ENDPOINT || "oss-cn-beijing.aliyuncs.com";
const bucket = env.OSS_BUCKET;
const accessKeyId = env.OSS_ACCESS_KEY_ID;
const accessKeySecret = env.OSS_ACCESS_KEY_SECRET;

if (!bucket || !accessKeyId || !accessKeySecret) {
  console.error("请在 .env 中配置 OSS_BUCKET / OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET");
  process.exit(1);
}

const region = endpoint.replace(/^https?:\/\//, "").split(".")[0];
const client = new OSS({ region, accessKeyId, accessKeySecret, bucket });

const files = [
  ["post/welcome.md", "welcome.md"],
  ["post/winter-2026.md", "winter-2026.md"],
  ["post/why-chtholly.md", "why-chtholly.md"],
];

for (const [objectKey, localName] of files) {
  const localFile = path.join(seedDir, localName);
  const body = fs.readFileSync(localFile, "utf-8");
  await client.put(objectKey, Buffer.from(body, "utf-8"), {
    headers: { "Content-Type": "text/markdown; charset=utf-8" },
  });
  await client.putACL(objectKey, "public-read");
  console.log(`OK ${objectKey} (${body.length} bytes)`);
}
