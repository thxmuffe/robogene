#!/usr/bin/env node
"use strict";

const fs = require("fs");
const path = require("path");
const { spawn } = require("child_process");

const SYSTEM_ENV_KEYS = new Set([
  "PATH",
  "HOME",
  "TMPDIR",
  "TMP",
  "TEMP",
  "TERM",
  "SHELL",
  "LANG",
  "LC_ALL",
  "LC_CTYPE",
  "USER",
  "LOGNAME",
  "PWD",
  "SHLVL",
  "SystemRoot",
  "SYSTEMROOT",
  "ComSpec",
  "COMSPEC",
  "PATHEXT",
  "APPDATA",
  "LOCALAPPDATA",
  "PROGRAMDATA",
  "PROGRAMFILES",
  "PROGRAMFILES(X86)",
  "HOMEDRIVE",
  "HOMEPATH",
  "USERNAME",
  "USERPROFILE",
  "WINDIR",
]);

const SYSTEM_ENV_PREFIXES = [
  "XDG_",
  "NPM_CONFIG_",
  "npm_config_",
  "NVM_",
  "VOLTA_",
  "ASDF_",
];

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

function isAllowedSystemEnvKey(key) {
  return SYSTEM_ENV_KEYS.has(key) || SYSTEM_ENV_PREFIXES.some((prefix) => key.startsWith(prefix));
}

function baseSystemEnv(env) {
  return Object.fromEntries(
    Object.entries(env).filter(([key]) => isAllowedSystemEnvKey(key))
  );
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
  const overlayEnvPath = path.resolve(root, overlayEnv);

  if (!fs.existsSync(overlayEnvPath)) {
    console.error(`Missing ${overlayEnv}`);
    process.exit(1);
  }

  const env = {
    ...baseSystemEnv(process.env),
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
