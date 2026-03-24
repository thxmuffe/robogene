import { defineConfig, devices } from '@playwright/test';
import path from 'path';

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: '**/*.spec.mjs',
  timeout: 120000,
  expect: {
    timeout: 15000,
  },
  fullyParallel: false,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://localhost:8081',
    trace: 'on-first-retry',
    actionTimeout: 15000,
    navigationTimeout: 15000,
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: [
    {
      command: 'npm run test:db:start',
      port: 10002, // Azurite Table service
      reuseExistingServer: !process.env.CI,
      timeout: 120000,
    },
    {
      command: 'npx http-server dist/release/webapp -p 8081 -c-1',
      port: 8081,
      reuseExistingServer: !process.env.CI,
      timeout: 120000,
    },
    {
      command: 'func start --script-root src/host --port 7072 --cors http://localhost:8081',
      port: 7072,
      env: {
        WEBAPI_PORT: '7072',
        ROBOGENE_ALLOWED_ORIGIN: 'http://localhost:8081',
        FUNCTIONS_WORKER_RUNTIME: 'node',
      },
      reuseExistingServer: !process.env.CI,
      timeout: 120000,
    }
  ],
});
