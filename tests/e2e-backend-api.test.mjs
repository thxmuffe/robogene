import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import net from 'node:net';

const shouldRun = process.env.ROBOGENE_RUN_E2E === '1';
const timeoutMs = Number(process.env.ROBOGENE_E2E_TIMEOUT_MS || 45000);
const preferredPort = Number(process.env.ROBOGENE_E2E_PORT || 7081);

function hasFuncCoreTools() {
  const result = spawnSync('func', ['--version'], { stdio: 'ignore' });
  return result.status === 0;
}

async function waitForHostReady(proc, readyPattern, timeout) {
  let logs = '';

  const append = (chunk) => {
    const text = chunk.toString();
    logs += text;
    if (logs.length > 12000) {
      logs = logs.slice(-12000);
    }
    return text;
  };

  return await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(new Error(`Timed out waiting for Functions host to start. Logs:\n${logs}`));
    }, timeout);

    const onData = (chunk) => {
      const text = append(chunk);
      if (readyPattern.test(text) || readyPattern.test(logs)) {
        clearTimeout(timer);
        proc.stdout.off('data', onData);
        proc.stderr.off('data', onData);
        resolve();
      }
    };

    proc.stdout.on('data', onData);
    proc.stderr.on('data', onData);

    proc.once('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`Functions host exited early with code ${code}. Logs:\n${logs}`));
    });
  });
}

async function stopHost(proc) {
  if (!proc || proc.exitCode !== null) return;
  proc.kill('SIGTERM');
  try {
    await once(proc, 'exit');
  } catch {
    proc.kill('SIGKILL');
  }
}

async function getFreePort(preferred = 0) {
  const tryPort = (port) =>
    new Promise((resolve, reject) => {
      const server = net.createServer();
      server.unref();
      server.on('error', reject);
      server.listen({ port, host: '127.0.0.1' }, () => {
        const address = server.address();
        const resolved = typeof address === 'object' && address ? address.port : port;
        server.close(() => resolve(resolved));
      });
    });

  if (preferred > 0) {
    try {
      return await tryPort(preferred);
    } catch (err) {
      if (err && err.code === 'EPERM') return null;
      return await tryPort(0);
    }
  }
  try {
    return await tryPort(0);
  } catch (err) {
    if (err && err.code === 'EPERM') return null;
    throw err;
  }
}

test('backend API e2e: state and generate-frame flow', { skip: !shouldRun || !hasFuncCoreTools() }, async (t) => {
  const port = await getFreePort(preferredPort);
  if (!port) {
    t.skip('Port binding is not permitted in this environment.');
    return;
  }
  const host = spawn('npm', ['--prefix', 'backend', 'run', 'start', '--', '--port', String(port), '--verbose'], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      ROBOGENE_ALLOWED_ORIGIN: `http://localhost:${port}`,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  try {
    await waitForHostReady(host, /Host started|Job host started|Functions:/, timeoutMs);

    const stateRes = await fetch(`http://localhost:${port}/api/state`, {
      headers: { 'Cache-Control': 'no-store' },
    });
    assert.equal(stateRes.status, 200, 'state endpoint should return 200');

    const state = await stateRes.json();
    assert.ok(Array.isArray(state.frames), 'state.frames should be an array');
    assert.ok(state.frames.length >= 1, 'state should include at least one frame');

    const candidate = state.frames.find((f) => typeof f.frameId === 'string' && f.frameId.length > 0);
    assert.ok(candidate, 'state should include a frame with frameId');

    const genRes = await fetch(`http://localhost:${port}/api/generate-frame`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
      },
      body: JSON.stringify({
        frameId: candidate.frameId,
        direction: 'E2E test direction',
      }),
    });

    assert.ok([202, 409].includes(genRes.status), `generate-frame should return 202 or 409, got ${genRes.status}`);

    const afterRes = await fetch(`http://localhost:${port}/api/state`, {
      headers: { 'Cache-Control': 'no-store' },
    });
    assert.equal(afterRes.status, 200, 'state endpoint should still return 200 after queue attempt');

    const afterState = await afterRes.json();
    assert.ok(Array.isArray(afterState.frames), 'afterState.frames should be an array');
  } finally {
    await stopHost(host);
  }
});
