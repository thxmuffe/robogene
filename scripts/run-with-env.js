#!/usr/bin/env node
"use strict";

const fs = require("fs");
const path = require("path");
const { spawn } = require("child_process");

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

function usage() {
  console.error("Usage: node scripts/run-with-env.js <overlay-env-file> -- <command...>");
}

function main() {
  const args = process.argv.slice(2);
  const separator = args.indexOf("--");
  if (args.length < 3 || separator <= 0 || separator === args.length - 1) {
    usage();
    process.exit(1);
  }

  const overlayEnv = args[0];
  const command = args.slice(separator + 1);

  const root = process.cwd();
  const baseEnvPath = path.join(root, "robogen.debug.env");
  const overlayEnvPath = path.resolve(root, overlayEnv);

  if (!fs.existsSync(baseEnvPath)) {
    console.error("Missing robogen.debug.env");
    process.exit(1);
  }
  if (!fs.existsSync(overlayEnvPath)) {
    console.error(`Missing ${overlayEnv}`);
    process.exit(1);
  }

  const env = {
    ...process.env,
    ...parseEnvFile(baseEnvPath),
    ...parseEnvFile(overlayEnvPath),
  };

  const executable =
    process.platform === "win32" && command[0] === "npm" ? "npm.cmd" : command[0];

  const child = spawn(executable, command.slice(1), {
    stdio: "inherit",
    shell: false,
    detached: false,
    env,
  });

  const forwardSignal = (signal) => {
    if (child.exitCode == null && !child.killed) {
      try {
        child.kill(signal);
      } catch {}
    }
  };

  process.on("SIGINT", () => forwardSignal("SIGINT"));
  process.on("SIGTERM", () => forwardSignal("SIGTERM"));
  process.on("SIGHUP", () => forwardSignal("SIGHUP"));
  process.on("exit", () => forwardSignal("SIGTERM"));

  child.on("exit", (code) => process.exit(code || 0));
  child.on("error", (err) => {
    console.error(String(err.message || err));
    process.exit(1);
  });
}

main();
