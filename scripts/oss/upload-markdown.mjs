/**
 * 一次性 OSS 操作：上传 Markdown 并设置 public-read
 * 用法：node scripts/oss/upload-markdown.mjs [objectKey] [localFile]
 * 环境变量从仓库根目录 .env 读取（勿提交密钥）
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import OSS from "ali-oss";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(__dirname, "../..");

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

const client = new OSS({
  region,
  accessKeyId,
  accessKeySecret,
  bucket,
});

const objectKey = process.argv[2];
const localFile = process.argv[3];

if (!objectKey || !localFile) {
  console.error("用法: node upload-markdown.mjs <objectKey> <localFile>");
  process.exit(1);
}

const body = fs.readFileSync(localFile, "utf-8");

await client.put(objectKey, Buffer.from(body, "utf-8"), {
  headers: { "Content-Type": "text/markdown; charset=utf-8" },
});
await client.putACL(objectKey, "public-read");

console.log(`OK ${objectKey} (${body.length} bytes, public-read)`);
