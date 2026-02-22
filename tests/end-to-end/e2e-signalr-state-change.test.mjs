import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { once } from 'node:events';
import http from 'node:http';
import net from 'node:net';

const shouldRun = process.env.ROBOGENE_RUN_E2E === '1';
const timeoutMs = Number(process.env.ROBOGENE_E2E_TIMEOUT_MS || 45000);
const preferredApiPort = Number(process.env.ROBOGENE_E2E_PORT || 7081);
const preferredSignalRPort = Number(process.env.ROBOGENE_E2E_SIGNALR_PORT || 7381);

function hasFuncCoreTools() {
  const result = spawnSync('func', ['--version'], { stdio: 'ignore' });
  return result.status === 0;
}

async function waitForHostReady(proc, readyPattern, timeout) {
  let logs = '';

  const append = (chunk) => {
    const text = chunk.toString();
    logs += text;
    if (logs.length > 14000) logs = logs.slice(-14000);
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

function killOrphanFuncByPort(port) {
  if (!port) return;
  spawnSync('pkill', ['-f', `func start --script-root src/api_host --port ${port}`], { stdio: 'ignore' });
}

async function startApiHostWithRetry({ maxAttempts = 4, signalRPort }) {
  let lastError = null;
  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    const apiPort = await getFreePort(preferredApiPort);
    const host = spawn('npm', ['run', 'api_host:start', '--', '--port', String(apiPort), '--verbose'], {
      cwd: process.cwd(),
      env: {
        ...process.env,
        ROBOGENE_ALLOWED_ORIGIN: `http://localhost:${apiPort}`,
        ROBOGENE_MOCK_IMAGE_SUCCESS: '1',
        FUNCTIONS_WORKER_RUNTIME: 'node',
        AzureSignalRConnectionString: `Endpoint=http://127.0.0.1:${signalRPort};AccessKey=test-key;Version=1.0;`,
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    try {
      await waitForHostReady(host, /Host started|Job host started|Functions:/, timeoutMs);
      return { apiPort, host };
    } catch (err) {
      lastError = err;
      await stopHost(host);
      if (!/Port \d+ is unavailable/i.test(String(err?.message || ''))) {
        break;
      }
    }
  }
  throw lastError || new Error('Failed to start API host');
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

function createSignalRStubServer() {
  const requests = [];
  const server = http.createServer(async (req, res) => {
    if (req.method !== 'POST' || !req.url?.startsWith('/api/v1/hubs/')) {
      res.statusCode = 404;
      res.end('not found');
      return;
    }

    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const text = Buffer.concat(chunks).toString('utf8');
    let body = null;
    try {
      body = JSON.parse(text);
    } catch {
      body = { parseError: true, raw: text };
    }

    requests.push({
      url: req.url,
      headers: req.headers,
      body,
      receivedAt: Date.now(),
    });

    res.statusCode = 202;
    res.setHeader('Content-Type', 'application/json');
    res.end('{"ok":true}');
  });

  return { server, requests };
}

async function closeServer(server) {
  if (!server) return;
  server.closeAllConnections?.();
  const closed = await Promise.race([
    new Promise((resolve) => server.close(() => resolve(true))),
    new Promise((resolve) => setTimeout(() => resolve(false), 1200)),
  ]);
  if (!closed) {
    server.closeIdleConnections?.();
    server.closeAllConnections?.();
  }
}

async function waitFor(predicate, timeout, interval = 100) {
  const deadline = Date.now() + timeout;
  // eslint-disable-next-line no-constant-condition
  while (true) {
    if (predicate()) return;
    if (Date.now() >= deadline) throw new Error('Timed out waiting for condition');
    await new Promise((resolve) => setTimeout(resolve, interval));
  }
}

test('web API e2e: mocked generation publishes SignalR stateChanged', { skip: !shouldRun || !hasFuncCoreTools() }, async () => {
  const signalRPort = await getFreePort(preferredSignalRPort);
  const { server: signalRServer, requests: signalRRequests } = createSignalRStubServer();

  await new Promise((resolve) => signalRServer.listen(signalRPort, '127.0.0.1', resolve));

  const { apiPort, host } = await startApiHostWithRetry({ signalRPort });
  const baseUrl = `http://localhost:${apiPort}`;

  try {
    const stateRes = await fetch(`${baseUrl}/api/state`, {
      headers: { 'Cache-Control': 'no-store' },
    });
    assert.equal(stateRes.status, 200, 'state endpoint should return 200');
    const state = await stateRes.json();
    assert.ok(Array.isArray(state.frames) && state.frames.length > 0, 'state should include frames');

    const frame = state.frames.find((f) => typeof f.frameId === 'string' && f.frameId.length > 0);
    assert.ok(frame, 'state should include a frameId');

    const genRes = await fetch(`${baseUrl}/api/generate-frame`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
      },
      body: JSON.stringify({
        frameId: frame.frameId,
        direction: 'SignalR mocked generation test',
      }),
    });
    assert.ok([202, 409].includes(genRes.status), `generate-frame should return 202 or 409, got ${genRes.status}`);

    await waitFor(
      () =>
        signalRRequests.some((r) => r.body?.target === 'stateChanged')
        && signalRRequests.some((r) => r.body?.target === 'stateChanged' && r.body?.arguments?.[0]?.reason === 'ready'),
      10000
    );

    const finalStateRes = await fetch(`${baseUrl}/api/state`, {
      headers: { 'Cache-Control': 'no-store' },
    });
    assert.equal(finalStateRes.status, 200, 'final state endpoint should return 200');
    const finalState = await finalStateRes.json();
    const finalFrame = (finalState.frames || []).find((f) => f.frameId === frame.frameId);
    assert.ok(finalFrame, 'target frame should still exist');
    assert.equal(finalFrame.status, 'ready', 'target frame should be ready after mocked generation');
    assert.ok(String(finalFrame.imageDataUrl || '').startsWith('data:image/'), 'ready frame should include image data url');
  } finally {
    await stopHost(host);
    killOrphanFuncByPort(apiPort);
    await closeServer(signalRServer);
  }
});
