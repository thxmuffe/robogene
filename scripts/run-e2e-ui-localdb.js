#!/usr/bin/env node
"use strict";

const fs = require("fs");
const { spawn, spawnSync } = require("child_process");
const net = require("net");
const path = require("path");

const AZURITE_DIR = ".tmp/azurite";
const AZURITE_STARTUP_TIMEOUT_MS = 20000;
const AZURITE_PORTS = [10000, 10001, 10002];

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function waitForPort(port, timeoutMs) {
  const startedAt = Date.now();
  return new Promise((resolve, reject) => {
    const tryConnect = () => {
      const socket = net.createConnection({ host: "127.0.0.1", port });
      socket.once("connect", () => {
        socket.destroy();
        resolve();
      });
      socket.once("error", () => {
        socket.destroy();
        if (Date.now() - startedAt >= timeoutMs) {
          reject(new Error(`Timed out waiting for Azurite port ${port}.`));
          return;
        }
        setTimeout(tryConnect, 250);
      });
    };
    tryConnect();
  });
}

function killChild(child, signal) {
  if (!child || child.exitCode !== null) return;
  try {
    child.kill(signal);
  } catch {}
}

function resolveAzuriteEntry() {
  const packageJsonPath = require.resolve("azurite/package.json");
  const packageDir = path.dirname(packageJsonPath);
  const { bin } = JSON.parse(fs.readFileSync(packageJsonPath, "utf8"));
  return path.join(packageDir, bin.azurite);
}

function resolvePackageBin(pkgName, binName) {
  const packageJsonPath = require.resolve(`${pkgName}/package.json`);
  const packageDir = path.dirname(packageJsonPath);
  const { bin } = JSON.parse(fs.readFileSync(packageJsonPath, "utf8"));
  return path.join(packageDir, typeof bin === "string" ? bin : bin[binName]);
}

function formatAzuriteExit(code, signal) {
  if (signal) return `Azurite exited before startup (signal ${signal}).`;
  return `Azurite exited before startup (code ${code ?? "unknown"}).`;
}

function waitForAzuriteReady(child) {
  return Promise.race([
    Promise.all([
      ...AZURITE_PORTS.map((port) => waitForPort(port, AZURITE_STARTUP_TIMEOUT_MS)),
    ]),
    new Promise((_, reject) => {
      child.once("error", (err) => reject(err));
      child.once("exit", (code, signal) => reject(new Error(formatAzuriteExit(code, signal))));
    }),
  ]);
}

function stopProcessesOnPorts(ports) {
  const killPortCli = resolvePackageBin("kill-port", "kill-port");
  const result = spawnSync(process.execPath, [killPortCli, ...ports.map(String)], {
    stdio: "inherit",
    shell: false,
  });
  if (result.status !== 0) {
    throw new Error(`Failed to clear Azurite ports (${ports.join(", ")}).`);
  }
}

async function stopAzurite(child) {
  if (!child || child.exitCode !== null) return;
  killChild(child, "SIGINT");
  await sleep(1000);
  killChild(child, "SIGKILL");
  await new Promise((resolve) => {
    child.once("exit", resolve);
    setTimeout(resolve, 1000);
  });
}

async function main() {
  stopProcessesOnPorts(AZURITE_PORTS);
  fs.rmSync(AZURITE_DIR, { recursive: true, force: true });
  const azuriteEntry = resolveAzuriteEntry();
  const azurite = spawn(
    process.execPath,
    [azuriteEntry, "--silent", "--location", AZURITE_DIR, "--blobHost", "127.0.0.1", "--queueHost", "127.0.0.1", "--tableHost", "127.0.0.1"],
    { stdio: "inherit", shell: false }
  );

  let finished = false;
  const cleanup = async () => {
    if (finished) return;
    finished = true;
    await stopAzurite(azurite);
  };

  process.on("SIGINT", async () => {
    await cleanup();
    process.exit(130);
  });
  process.on("SIGTERM", async () => {
    await cleanup();
    process.exit(143);
  });

  try {
    await waitForAzuriteReady(azurite);

    const test = spawn(
      process.platform === "win32" ? "npm.cmd" : "npm",
      ["run", "test:e2e:ui:env"],
      { stdio: "inherit", shell: false }
    );

    test.on("exit", async (code) => {
      await cleanup();
      process.exit(code || 0);
    });
    test.on("error", async (err) => {
      console.error(String(err.message || err));
      await cleanup();
      process.exit(1);
    });
  } catch (err) {
    console.error(String(err.message || err));
    await cleanup();
    process.exit(1);
  }
}

main();
