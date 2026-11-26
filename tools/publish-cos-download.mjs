#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, stat, writeFile, mkdir } from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import COS from "../server/scf/node_modules/cos-nodejs-sdk-v5/index.js";

const root = path.resolve(import.meta.dirname, "..");
const apkArg = process.argv[2];
const buildLabel = (process.argv[3] || "release").replace(/[^0-9A-Za-z._-]/g, "-");

if (!apkArg) {
  console.error("Usage: node tools/publish-cos-download.mjs <apk> [build-label]");
  process.exit(2);
}

async function loadEnv(file) {
  const text = await readFile(file, "utf8");
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const split = line.indexOf("=");
    if (split < 1) continue;
    const key = line.slice(0, split).trim();
    const value = line.slice(split + 1).trim().replace(/^(['"])(.*)\1$/, "$2");
    if (process.env[key] === undefined) process.env[key] = value;
  }
}

await loadEnv(path.join(root, ".tencent-cloud.env"));
const Bucket = process.env.COS_BUCKET;
const Region = process.env.COS_REGION;
const SecretId = process.env.TENCENT_SECRET_ID;
const SecretKey = process.env.TENCENT_SECRET_KEY;
if (!Bucket || !Region || !SecretId || !SecretKey) throw new Error("缺少腾讯云 COS 配置");

const apkPath = path.resolve(process.cwd(), apkArg);
const bytes = await readFile(apkPath);
const info = await stat(apkPath);
const sha256 = createHash("sha256").update(bytes).digest("hex");
const version = path.basename(apkPath).match(/\b(\d+\.\d+\.\d+)\b/)?.[1];
if (!version) throw new Error("APK 文件名中缺少 x.y.z 版本号");

const chunkSize = 20 * 1024 * 1024;
const partBuffers = [];
for (let offset = 0; offset < bytes.length; offset += chunkSize) {
  partBuffers.push(bytes.subarray(offset, Math.min(bytes.length, offset + chunkSize)));
}
const folder = `downloads/PromX-${version}-${buildLabel}`;
const parts = partBuffers.map((body, index) => ({
  key: `${folder}/${path.basename(apkPath, ".apk")}-${String(index + 1).padStart(2, "0")}.part`,
  size: body.byteLength,
  body,
}));

const cos = new COS({ SecretId, SecretKey });
const base = { Bucket, Region };

const upload = (Key, Body, ContentType, CacheControl, Metadata = {}) => new Promise((resolve, reject) => {
  cos.putObject({ ...base, Key, Body, ContentType, CacheControl, Metadata, ACL: "public-read" }, (error, data) => {
    if (error) reject(error); else resolve(data);
  });
});

const head = (Key) => new Promise((resolve, reject) => {
  cos.headObject({ ...base, Key }, (error, data) => {
    if (error) reject(error); else resolve(data);
  });
});

await Promise.all(parts.map((part) => upload(part.key, part.body, "application/octet-stream",
  "public,max-age=31536000,immutable")));

for (const part of parts) {
  const remote = await head(part.key);
  if (Number(remote.headers?.["content-length"]) !== part.size) {
    throw new Error(`上传长度校验失败 ${part.key}`);
  }
}

const manifest = {
  version,
  build: buildLabel,
  apkName: path.basename(apkPath),
  size: info.size,
  sha256,
  publishedAt: new Date().toISOString(),
  parts: parts.map(({ key, size }) => ({
    url: `https://${Bucket}.cos.${Region}.myqcloud.com/${key}`,
    size,
  })),
};

await upload("downloads/latest.json", JSON.stringify(manifest, null, 2), "application/json; charset=utf-8",
  "public,max-age=60,must-revalidate");
await upload(`${folder}/${path.basename(apkPath, ".apk")}.sha256`, `${sha256}  ${path.basename(apkPath)}\n`,
  "text/plain; charset=utf-8", "public,max-age=31536000,immutable");

await mkdir(path.join(root, "output"), { recursive: true });
await writeFile(path.join(root, "output", "download-latest.json"), `${JSON.stringify(manifest, null, 2)}\n`);
console.log(JSON.stringify(manifest, null, 2));
