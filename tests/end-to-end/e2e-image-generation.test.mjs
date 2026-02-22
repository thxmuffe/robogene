import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import net from 'node:net';

const shouldRun = process.env.ROBOGENE_RUN_IMAGE_GEN_E2E === '1';
const timeoutMs = Number(process.env.ROBOGENE_IMAGE_GEN_TIMEOUT_MS || 180000);
const preferredPort = Number(process.env.ROBOGENE_E2E_IMAGE_PORT || process.env.ROBOGENE_E2E_PORT || 7082);
const pollIntervalMs = Number(process.env.ROBOGENE_IMAGE_GEN_POLL_MS || 2000);

function hasFuncCoreTools() {
  const result = spawnSync('func', ['--version'], { stdio: 'ignore' });
  return result.status === 0;
}

async function waitForHostReady(proc, readyPattern, timeout) {
  let logs = '';

  const append = (chunk) => {
    const text = chunk.toString();
    logs += text;
    if (logs.length > 16000) logs = logs.slice(-16000);
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
  const exited = await Promise.race([
    once(proc, 'exit').then(() => true),
    new Promise((resolve) => setTimeout(() => resolve(false), 1500)),
  ]);
  if (!exited) {
    proc.kill('SIGKILL');
    await once(proc, 'exit');
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
    } catch {
      return await tryPort(0);
    }
  }
  return await tryPort(0);
}

async function fetchState(baseUrl) {
  const res = await fetch(`${baseUrl}/api/state`, {
    headers: { 'Cache-Control': 'no-store' },
  });
  assert.equal(res.status, 200, 'state endpoint should return 200');
  return await res.json();
}

test('web API e2e: real image generation (opt-in)', { skip: !shouldRun || !hasFuncCoreTools() }, async (t) => {
  const apiKey = String(process.env.OPENAI_API_KEY || '').trim();
  if (!apiKey) {
    t.skip('OPENAI_API_KEY is required for real image generation e2e.');
    return;
  }

  const port = await getFreePort(preferredPort);
  const host = spawn('npm', ['run', 'api_host:start', '--', '--port', String(port), '--verbose'], {
    cwd: process.cwd(),
    env: {
      ...process.env,
      ROBOGENE_ALLOWED_ORIGIN: `http://localhost:${port}`,
      FUNCTIONS_WORKER_RUNTIME: 'node',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  const baseUrl = `http://localhost:${port}`;

  try {
    await waitForHostReady(host, /Host started|Job host started|Functions:/, 45000);

    const initial = await fetchState(baseUrl);
    assert.ok(Array.isArray(initial.frames) && initial.frames.length > 0, 'state should contain frames');

    const target =
      initial.frames.find((f) => String(f.status || '').toLowerCase() === 'draft')
      || initial.frames.find((f) => typeof f.frameId === 'string' && f.frameId.length > 0);
    assert.ok(target, 'a target frame should exist');

    const enqueueRes = await fetch(`${baseUrl}/api/generate-frame`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
      },
      body: JSON.stringify({
        frameId: target.frameId,
        direction: 'E2E real generation test prompt',
      }),
    });

    assert.ok([202, 409].includes(enqueueRes.status), `generate-frame should return 202 or 409, got ${enqueueRes.status}`);

    const deadline = Date.now() + timeoutMs;
    let lastFrame = null;

    while (Date.now() < deadline) {
      const state = await fetchState(baseUrl);
      lastFrame = (state.frames || []).find((f) => f.frameId === target.frameId) || null;
      if (!lastFrame) break;

      const s = String(lastFrame.status || '').toLowerCase();
      if (s === 'ready') {
        assert.ok(String(lastFrame.imageDataUrl || '').startsWith('data:image/'), 'ready frame should include image data url');
        return;
      }
      if (s === 'failed') {
        assert.fail(`generation failed: ${lastFrame.error || 'unknown error'}`);
      }

      await new Promise((resolve) => setTimeout(resolve, pollIntervalMs));
    }

    const terminalState = lastFrame ? String(lastFrame.status || '') : 'missing';
    assert.fail(`timed out waiting for frame readiness; last status: ${terminalState}`);
  } finally {
    await stopHost(host);
  }
});
