import { defineConfig, devices } from '@playwright/test';

const recordAll = process.env['E2E_VIDEO'] === 'on';

// Reference project e2e tests. Needs the SAP Commerce server, the storefront and core-customize/scripts/stripe-listen.sh.
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  timeout: 300_000,
  expect: { timeout: 30_000 },
  retries: 0,
  workers: 1,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200',
    ignoreHTTPSErrors: true,
    trace: recordAll ? 'on' : 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: recordAll ? { mode: 'on', size: { width: 1280, height: 720 } } : 'retain-on-failure',
  },
  projects: [
    { name: 'checkout', testDir: './e2e/checkout' },
    { name: 'after-purchase', testDir: './e2e/after-purchase' },
    { name: 'webhooks', testDir: './e2e/webhooks' },
  ],
});
