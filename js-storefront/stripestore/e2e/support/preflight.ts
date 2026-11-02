import { execFileSync } from 'node:child_process';
import { request } from '@playwright/test';
import { OCC_URL } from './occ';

const TIMEOUT_MS = 10_000;
const APP_TITLE = 'Stripestore';

/** Fails fast, with a clear message, when the storefront, the HAC or stripe listen is not ready. */
export async function preflight(): Promise<void> {
  await checkStorefront();
  await checkHac();
  checkStripeListen();
}

async function checkStorefront(): Promise<void> {
  const baseUrl = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';
  const api = await request.newContext({ ignoreHTTPSErrors: true });
  try {
    let html: string;
    try {
      html = await (await api.get(baseUrl, { timeout: TIMEOUT_MS })).text();
    } catch {
      throw new Error(`E2E_BASE_URL ${baseUrl} does not answer`);
    }
    const title = /<title>([^<]*)<\/title>/i.exec(html)?.[1]?.trim();
    if (title !== APP_TITLE) {
      throw new Error(`E2E_BASE_URL ${baseUrl} does not serve the stripestore app (title "${title ?? ''}")`);
    }
  } finally {
    await api.dispose();
  }
}

async function checkHac(): Promise<void> {
  const hacUrl = `${OCC_URL}/hac/`;
  const api = await request.newContext({ ignoreHTTPSErrors: true });
  try {
    let status: number;
    try {
      status = (await api.get(hacUrl, { timeout: TIMEOUT_MS, maxRedirects: 0 })).status();
    } catch {
      throw new Error(`E2E_OCC_URL ${OCC_URL} does not answer (${hacUrl})`);
    }
    if (status !== 200 && status !== 302) {
      throw new Error(`E2E_OCC_URL ${OCC_URL} gives HTTP ${status} for ${hacUrl}, expected 200 or 302`);
    }
  } finally {
    await api.dispose();
  }
}

function checkStripeListen(): void {
  const listeners = execFileSync('ps', ['-axo', 'command']).toString().split('\n').filter((c) => /stripe listen .*--forward-to/.test(c));
  if (!listeners.length) {
    throw new Error('Start core-customize/scripts/stripe-listen.sh first: the tests need the webhooks');
  }
}
