#!/usr/bin/env node
"use strict";

const fs = require("fs");
const { spawn } = require("child_process");
const net = require("net");

const AZURITE_DIR = ".tmp/azurite";

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
  fs.rmSync(AZURITE_DIR, { recursive: true, force: true });
  const azurite = spawn(
    process.platform === "win32" ? "npx.cmd" : "npx",
    ["azurite", "--silent", "--location", AZURITE_DIR, "--blobHost", "127.0.0.1", "--queueHost", "127.0.0.1", "--tableHost", "127.0.0.1"],
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
    await waitForPort(10000, 10000);
    await waitForPort(10001, 10000);
    await waitForPort(10002, 10000);

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
