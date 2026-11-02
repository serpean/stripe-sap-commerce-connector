import { defineConfig, devices } from '@playwright/test';

const recordAll = process.env['E2E_VIDEO'] === 'on';
// Set by e2e/run.mjs for each phase, so that one phase does not delete the videos, traces and report of another.
const phase = process.env['E2E_PHASE'];

/**
 * Specs that change the server configuration for all workers (setConfig), or that depend on the order of the webhook
 * events or on the count of all stored events. They run with one worker.
 */
const SERIAL = [
  'after-purchase/capture-automatic.spec.ts',
  'checkout/webhook-amount-mismatch.spec.ts',
  'checkout/race.spec.ts',
  'checkout/multibanco-paid.spec.ts',
  'webhooks/invalid-signature.spec.ts',
];

/** Specs that wait for Stripe for real: no Stripe test input gives their result at once. */
const SLOW = ['checkout/sepa-pending.spec.ts', 'checkout/sepa-failed.spec.ts'];

// Reference project e2e tests. Needs the SAP Commerce server, the storefront and core-customize/scripts/stripe-listen.sh.
// npm run e2e runs the projects one after the other, each with its own workers (see e2e/run.mjs).
export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  outputDir: phase ? `test-results/${phase}` : 'test-results',
  timeout: 300_000,
  expect: { timeout: 30_000 },
  retries: 1,
  // One worker unless the command line says otherwise: the serial project must never share the server.
  workers: 1,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: phase ? `playwright-report/${phase}` : 'playwright-report' }],
    // e2e/run.mjs reads this file to name the flaky tests (they passed only on a retry).
    ...(phase ? [['json', { outputFile: `test-results/results-${phase}.json` }] as const] : []),
  ],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200',
    ignoreHTTPSErrors: true,
    trace: recordAll ? 'on' : 'on-first-retry',
    screenshot: 'only-on-failure',
    video: recordAll ? { mode: 'on', size: { width: 1280, height: 720 } } : 'on-first-retry',
  },
  projects: [
    { name: 'parallel', testIgnore: [...SERIAL, ...SLOW], fullyParallel: true },
    { name: 'serial', testMatch: SERIAL },
    { name: 'slow', testMatch: SLOW, fullyParallel: true },
  ],
});
