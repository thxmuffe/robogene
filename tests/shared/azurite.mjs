import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { spawn } from 'node:child_process';

import { stopProcess } from './async.mjs';

const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const azuriteLocation = path.join(repoRoot, '.tmp', 'azurite-integration');
const azuriteBin = path.join(
  repoRoot,
  'node_modules',
  '.bin',
  process.platform === 'win32' ? 'azurite.cmd' : 'azurite',
);

function canConnect(port, host = '127.0.0.1') {
  return new Promise((resolve) => {
    const socket = net.createConnection({ host, port });
    const done = (result) => {
      socket.removeAllListeners();
      socket.destroy();
      resolve(result);
    };

    socket.once('connect', () => done(true));
    socket.once('error', () => done(false));
  });
}

async function waitForPort(port, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await canConnect(port)) return;
    await new Promise((resolve) => setTimeout(resolve, 200));
  }

  throw new Error(`Timed out waiting for Azurite port ${port}`);
}

export async function startAzurite() {
  if ((await canConnect(10000)) && (await canConnect(10001)) && (await canConnect(10002))) {
    return {
      owned: false,
      close: async () => {},
    };
  }

  if (!fs.existsSync(azuriteBin)) {
    throw new Error(`Azurite binary not found at ${azuriteBin}`);
  }

  fs.rmSync(azuriteLocation, { recursive: true, force: true });
  fs.mkdirSync(azuriteLocation, { recursive: true });

  const proc = spawn(
    azuriteBin,
    [
      '--silent',
      '--skipApiVersionCheck',
      '--location',
      azuriteLocation,
      '--blobHost',
      '127.0.0.1',
      '--queueHost',
      '127.0.0.1',
      '--tableHost',
      '127.0.0.1',
    ],
    {
      cwd: repoRoot,
      shell: process.platform === 'win32',
      stdio: 'ignore',
    },
  );

  await waitForPort(10000);
  await waitForPort(10001);
  await waitForPort(10002);

  return {
    owned: true,
    close: async () => {
      await stopProcess(proc);
    },
  };
}
