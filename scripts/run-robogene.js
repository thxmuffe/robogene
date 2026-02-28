#!/usr/bin/env node
"use strict";

const fs = require("fs");
const fsp = fs.promises;
const path = require("path");
const net = require("net");
const http = require("http");
const crypto = require("crypto");
const { spawn } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
process.chdir(ROOT);

function parseArgs(argv) {
  let mode = "debug";
  for (const arg of argv) {
    if (arg === "--release") mode = "release";
    else if (arg === "--debug") mode = "debug";
    else throw new Error(`Usage: node scripts/run-robogene.js [--debug|--release]`);
  }
  return mode;
}

function parseEnvFile(filePath) {
  const parsed = {};
  const text = fs.readFileSync(filePath, "utf8");
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const idx = line.indexOf("=");
    if (idx <= 0) continue;
    const key = line.slice(0, idx).trim();
    let value = line.slice(idx + 1).trim();
    if (
      (value.startsWith('"') && value.endsWith('"')) ||
      (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    parsed[key] = value;
  }
  return parsed;
}

async function isPortInUse(port) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once("error", () => resolve(true));
    server.once("listening", () => {
      server.close(() => resolve(false));
    });
    server.listen(port, "0.0.0.0");
  });
}

function runNpm(args, extraEnv = {}) {
  const npmCmd = process.platform === "win32" ? "npm.cmd" : "npm";
  return spawn(npmCmd, args, {
    stdio: "inherit",
    shell: false,
    detached: process.platform !== "win32",
    env: { ...process.env, ...extraEnv },
  });
}

function ensureFileExists(filePath, hint) {
  if (!fs.existsSync(filePath)) {
    throw new Error(`Missing ${path.basename(filePath)}${hint ? `\n${hint}` : ""}`);
  }
}

async function ensureDir(dir) {
  await fsp.mkdir(dir, { recursive: true });
}

async function copyWebappAssets(outDir) {
  await ensureDir(outDir);
  await fsp.copyFile(path.join(ROOT, "src", "webapp", "index.html"), path.join(outDir, "index.html"));
  await fsp.copyFile(path.join(ROOT, "src", "webapp", "styles.css"), path.join(outDir, "styles.css"));
}

async function webappAssetsSnapshot() {
  const files = [
    path.join(ROOT, "src", "webapp", "index.html"),
    path.join(ROOT, "src", "webapp", "styles.css"),
  ];
  const hash = crypto.createHash("sha256");
  for (const filePath of files) {
    const stat = await fsp.stat(filePath);
    hash.update(filePath);
    hash.update(String(stat.mtimeMs));
    hash.update(String(stat.size));
  }
  return hash.digest("hex");
}

function createStaticServer(rootDir, port) {
  const server = http.createServer(async (req, res) => {
    try {
      const requestPath = new URL(req.url, `http://localhost:${port}`).pathname;
      const normalized = requestPath === "/" ? "/index.html" : requestPath;
      const fullPath = path.normalize(path.join(rootDir, normalized));
      if (!fullPath.startsWith(rootDir)) {
        res.statusCode = 403;
        res.end("Forbidden");
        return;
      }
      const data = await fsp.readFile(fullPath);
      if (fullPath.endsWith(".html")) res.setHeader("Content-Type", "text/html; charset=utf-8");
      if (fullPath.endsWith(".css")) res.setHeader("Content-Type", "text/css; charset=utf-8");
      if (fullPath.endsWith(".js")) res.setHeader("Content-Type", "application/javascript; charset=utf-8");
      res.statusCode = 200;
      res.end(data);
    } catch {
      res.statusCode = 404;
      res.end("Not Found");
    }
  });
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(port, "0.0.0.0", () => resolve(server));
  });
}

async function listFilesRecursive(startDir) {
  const out = [];
  async function walk(dir) {
    let entries = [];
    try {
      entries = await fsp.readdir(dir, { withFileTypes: true });
    } catch {
      return;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        await walk(full);
      } else if (entry.isFile()) {
        out.push(full);
      }
    }
  }
  await walk(startDir);
  return out;
}

async function apiSnapshot() {
  const files = [];
  const dirs = [path.join(ROOT, "src", "host"), path.join(ROOT, "src", "services")];
  for (const dir of dirs) {
    const listed = await listFilesRecursive(dir);
    files.push(...listed);
  }
  const fixed = ["shadow-cljs.edn", "deps.edn", "package.json", "package-lock.json"]
    .map((rel) => path.join(ROOT, rel))
    .filter((p) => fs.existsSync(p));
  files.push(...fixed);
  files.sort();
  const hash = crypto.createHash("sha256");
  for (const filePath of files) {
    const stat = await fsp.stat(filePath);
    hash.update(filePath);
    hash.update(String(stat.mtimeMs));
    hash.update(String(stat.size));
  }
  return hash.digest("hex");
}

function killChild(child) {
  if (!child || child.killed) return;
  if (process.platform === "win32" && child.pid) {
    spawn("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore", shell: false });
    return;
  }
  const pid = child.pid;
  if (!pid) return;
  try {
    // Kill the whole process group so npm children (func/shadow-cljs) do not survive.
    process.kill(-pid, "SIGTERM");
  } catch {
    try {
      child.kill("SIGTERM");
    } catch {}
  }
  setTimeout(() => {
    if (child.exitCode == null) {
      try {
        process.kill(-pid, "SIGKILL");
      } catch {
        try {
          child.kill("SIGKILL");
        } catch {}
      }
    }
  }, 1500);
}

async function waitForExit(child) {
  return new Promise((resolve, reject) => {
    child.once("exit", (code) => resolve(code || 0));
    child.once("error", reject);
  });
}

async function main() {
  const mode = parseArgs(process.argv.slice(2));
  const webappPort = Number(process.env.WEBAPP_PORT || 8080);
  const webapiPort = Number(process.env.WEBAPI_PORT || 7071);
  const webappDistDir = path.join(ROOT, "dist", mode, "webapp");
  const envFile = path.join(ROOT, "robogen.debug.env");
  const envExample = path.join(ROOT, "robogen.debug.env.example");

  ensureFileExists(
    envFile,
    `Create it from ${path.basename(envExample)} and fill in real values.`
  );
  Object.assign(process.env, parseEnvFile(envFile));

  if (!process.env.AzureWebJobsStorage && process.env.ROBOGENE_STORAGE_CONNECTION_STRING) {
    process.env.AzureWebJobsStorage = process.env.ROBOGENE_STORAGE_CONNECTION_STRING;
  }

  if (
    (process.env.ROBOGENE_IMAGE_GENERATOR || "openai") === "openai" &&
    !process.env.ROBOGENE_IMAGE_GENERATOR_KEY
  ) {
    throw new Error(
      "Missing ROBOGENE_IMAGE_GENERATOR_KEY in robogen.debug.env (required for ROBOGENE_IMAGE_GENERATOR=openai)."
    );
  }

  if (!process.env.ROBOGENE_ALLOWED_ORIGIN) {
    throw new Error(
      `Missing ROBOGENE_ALLOWED_ORIGIN in robogen.debug.env\nSet it explicitly, e.g. ROBOGENE_ALLOWED_ORIGIN='http://localhost:${webappPort},http://127.0.0.1:${webappPort}'`
    );
  }

  if (await isPortInUse(webapiPort)) {
    throw new Error(`Port ${webapiPort} is already in use. Stop that process first.`);
  }
  if (mode === "release" && (await isPortInUse(webappPort))) {
    throw new Error(`Port ${webappPort} is already in use. Stop that process first.`);
  }

  const webapiBuildCmd = mode === "release" ? "build:webapi" : "build:webapi:debug";
  const children = [];
  let staticServer = null;
  let apiHost = null;
  let webWatch = null;
  let watcherTimer = null;
  let stopping = false;
  let restarting = false;
  let lastSnapshot = "";
  let lastWebappAssetsSnapshot = "";

  const cleanup = () => {
    if (stopping) return;
    stopping = true;
    if (watcherTimer) clearInterval(watcherTimer);
    if (staticServer) staticServer.close();
    killChild(apiHost);
    killChild(webWatch);
    for (const child of children) killChild(child);
  };

  process.on("SIGINT", cleanup);
  process.on("SIGTERM", cleanup);
  process.on("exit", cleanup);

  console.log(`Building webapi ${mode} bundle...`);
  {
    const build = runNpm(["run", webapiBuildCmd]);
    children.push(build);
    const code = await waitForExit(build);
    if (code !== 0) process.exit(code);
  }

  await copyWebappAssets(webappDistDir);
  lastWebappAssetsSnapshot = await webappAssetsSnapshot();

  if (mode === "release") {
    console.log("Building webapp release bundle...");
    const buildWeb = runNpm(["run", "build"]);
    children.push(buildWeb);
    const code = await waitForExit(buildWeb);
    if (code !== 0) process.exit(code);
    staticServer = await createStaticServer(webappDistDir, webappPort);
  } else {
    webWatch = runNpm(["run", "watch"]);
    webWatch.once("exit", () => {
      if (!stopping) cleanup();
    });
  }

  function startApiHost() {
    apiHost = runNpm([
      "run",
      "host:start",
      "--",
      "--port",
      String(webapiPort),
      "--cors",
      process.env.ROBOGENE_ALLOWED_ORIGIN,
    ]);
    apiHost.once("exit", () => {
      if (!stopping && !restarting && mode === "release") cleanup();
    });
  }

  async function rebuildAndRestartApi() {
    if (restarting || stopping) return;
    restarting = true;
    console.log("API source/config changed. Rebuilding and restarting Azure Functions host...");
    const build = runNpm(["run", webapiBuildCmd]);
    const code = await waitForExit(build);
    if (code === 0) {
      killChild(apiHost);
      startApiHost();
      lastSnapshot = await apiSnapshot();
    } else {
      console.error("Webapi build failed. Keeping previous host process alive.");
    }
    restarting = false;
  }

  startApiHost();
  console.log(`Webapp: http://localhost:${webappPort}/index.html`);
  console.log(`Web API: http://localhost:${webapiPort}`);

  if (mode === "debug") {
    lastSnapshot = await apiSnapshot();
    watcherTimer = setInterval(async () => {
      if (stopping || restarting) return;
      const currentWebappAssets = await webappAssetsSnapshot();
      if (currentWebappAssets !== lastWebappAssetsSnapshot) {
        await copyWebappAssets(webappDistDir);
        lastWebappAssetsSnapshot = currentWebappAssets;
        console.log("Webapp static assets changed. Synced index.html/styles.css.");
      }
      const current = await apiSnapshot();
      if (current !== lastSnapshot) await rebuildAndRestartApi();
    }, 1000);
  }

  await new Promise(() => {});
}

main().catch((err) => {
  console.error(err.message || err);
  process.exit(1);
});
