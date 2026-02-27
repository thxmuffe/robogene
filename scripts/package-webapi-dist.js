#!/usr/bin/env node
"use strict";

const fs = require("fs");
const fsp = fs.promises;
const path = require("path");
const { spawn } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const HOST_SRC_DIR = path.join(ROOT, "src", "host");
const AI_SRC_DIR = path.join(ROOT, "ai", "robot emperor");
const WEBAPI_DIST_DIR = path.join(ROOT, "dist", "release", "webapi");
const APP_DIST_DIR = path.join(WEBAPI_DIST_DIR, "app");
const COMPILED_WEBAPI_JS = path.join(WEBAPI_DIST_DIR, "webapi_compiled.js");
const COMPILED_WEBAPI_HOST_JS = path.join(HOST_SRC_DIR, "dist", "main.js");
const WEBAPI_ZIP = path.join(WEBAPI_DIST_DIR, "webapi_dist.zip");

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      stdio: "inherit",
      shell: true,
      cwd: ROOT,
      ...options,
    });
    child.once("error", reject);
    child.once("exit", (code) => resolve(code || 0));
  });
}

async function exists(p) {
  try {
    await fsp.access(p);
    return true;
  } catch {
    return false;
  }
}

async function copyDirFiltered(from, to, options = {}) {
  const ignore = new Set(options.ignore || []);
  await fsp.mkdir(to, { recursive: true });
  const entries = await fsp.readdir(from, { withFileTypes: true });
  for (const entry of entries) {
    if (ignore.has(entry.name)) continue;
    const src = path.join(from, entry.name);
    const dst = path.join(to, entry.name);
    if (entry.isDirectory()) {
      await copyDirFiltered(src, dst, options);
    } else if (entry.isFile()) {
      await fsp.copyFile(src, dst);
    }
  }
}

async function makeZip(zipPath, sourceDir) {
  if (await exists(zipPath)) {
    await fsp.rm(zipPath, { force: true });
  }
  if (process.platform === "win32") {
    const code = await run(
      "powershell",
      [
        "-NoProfile",
        "-Command",
        `Compress-Archive -Path '${sourceDir}\\*' -DestinationPath '${zipPath}' -CompressionLevel Optimal`,
      ],
      { shell: false }
    );
    return code;
  }
  return run("zip", ["-rq", zipPath, "."], { cwd: sourceDir });
}

async function main() {
  if (!(await exists(COMPILED_WEBAPI_JS))) {
    console.error(`Missing compiled services output: ${COMPILED_WEBAPI_JS}`);
    console.error("Run: npm run build:webapi");
    process.exit(1);
  }
  if (!(await exists(COMPILED_WEBAPI_HOST_JS))) {
    console.error(`Missing compiled host output: ${COMPILED_WEBAPI_HOST_JS}`);
    console.error("Run: npm run build:webapi");
    process.exit(1);
  }

  await fsp.mkdir(WEBAPI_DIST_DIR, { recursive: true });
  await fsp.rm(APP_DIST_DIR, { recursive: true, force: true });
  await fsp.mkdir(APP_DIST_DIR, { recursive: true });

  await copyDirFiltered(HOST_SRC_DIR, APP_DIST_DIR, { ignore: ["local.settings.json"] });

  await fsp.copyFile(path.join(APP_DIST_DIR, "package.json"), path.join(APP_DIST_DIR, "package.host.json"));
  await fsp.copyFile(path.join(ROOT, "package.json"), path.join(APP_DIST_DIR, "package.json"));
  await fsp.copyFile(path.join(ROOT, "package-lock.json"), path.join(APP_DIST_DIR, "package-lock.json"));

  const npmCiCode = await run("npm", ["ci", "--omit=dev", "--ignore-scripts", "--no-audit", "--no-fund"], {
    cwd: APP_DIST_DIR,
  });
  if (npmCiCode !== 0) process.exit(npmCiCode);

  await fsp.rename(path.join(APP_DIST_DIR, "package.host.json"), path.join(APP_DIST_DIR, "package.json"));
  await fsp.rm(path.join(APP_DIST_DIR, "package-lock.json"), { force: true });

  await fsp.mkdir(path.join(APP_DIST_DIR, "dist"), { recursive: true });
  await fsp.copyFile(COMPILED_WEBAPI_JS, path.join(APP_DIST_DIR, "dist", "webapi_compiled.js"));

  if (await exists(AI_SRC_DIR)) {
    await copyDirFiltered(AI_SRC_DIR, path.join(APP_DIST_DIR, "ai", "robot emperor"));
  }

  const zipCode = await makeZip(WEBAPI_ZIP, APP_DIST_DIR);
  if (zipCode !== 0) process.exit(zipCode);

  console.log(`Assembled Function App: ${APP_DIST_DIR}`);
  console.log(`Packaged zip: ${WEBAPI_ZIP}`);
}

main().catch((err) => {
  console.error(err.message || err);
  process.exit(1);
});
