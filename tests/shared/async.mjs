import { once } from 'node:events';

export async function waitForHttpOk(url, timeoutMs, intervalMs = 500) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url, { headers: { 'Cache-Control': 'no-store' } });
      if (res.ok) return;
      lastError = new Error(`HTTP ${res.status}`);
    } catch (err) {
      lastError = err;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(`Timed out waiting for ${url}: ${lastError ? String(lastError.message || lastError) : 'unknown error'}`);
}

export async function stopProcess(proc, timeoutMs = 2500) {
  if (!proc || proc.exitCode !== null) return;
  proc.kill('SIGTERM');
  const exited = await Promise.race([
    once(proc, 'exit').then(() => true),
    new Promise((resolve) => setTimeout(() => resolve(false), timeoutMs)),
  ]);
  if (!exited) {
    proc.kill('SIGKILL');
    await once(proc, 'exit');
  }
}
