import { execa } from 'execa';
import fs from 'node:fs';

const AZURITE_DIR = ".tmp/azurite";
const AZURITE_PORTS = [10000, 10001, 10002];

async function main() {
  console.log('[e2e-local] Cleaning up Azurite ports...');
  try {
    await execa('npx', ['kill-port', ...AZURITE_PORTS.map(String)]);
  } catch (err) {
    // Ignore errors if ports are already free
  }
  
  console.log('[e2e-local] Cleaning Azurite data...');
  if (fs.existsSync(AZURITE_DIR)) {
    fs.rmSync(AZURITE_DIR, { recursive: true, force: true });
  }
  
  console.log('[e2e-local] Starting tests via Playwright...');
  // Playwright's webServer will start Azurite, but we ensured the ports are free here.
  try {
    const args = process.argv.slice(2);
    await execa('npm', ['run', 'test:e2e', '--', ...args], { stdio: 'inherit' });
  } catch (err) {
    process.exit(err.exitCode || 1);
  }
}

main().catch(err => {
  console.error(err);
  process.exit(1);
});
