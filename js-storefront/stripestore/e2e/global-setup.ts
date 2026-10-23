import { execFileSync } from 'node:child_process';
import { refillTestStock, setConfig } from './support/backend';
import { Currency, findProductCandidates } from './support/occ';

/** Short connector timings for the tests. They change the running server only; a restart reads local.properties. */
export const TEST_TIMINGS: Record<string, string> = {
  'stripe.webhook.graceperiod.seconds': '20',
  'stripe.payment.pending.retry.seconds': '10',
  'stripe.payment.pending.timeout.sepa_debit.seconds': '600',
  'stripe.capture.mode': 'manual',
};

export default async function globalSetup(): Promise<void> {
  const listeners = execFileSync('ps', ['-axo', 'command']).toString().split('\n').filter((c) => /stripe listen .*--forward-to/.test(c));
  if (!listeners.length) {
    throw new Error('Start core-customize/scripts/stripe-listen.sh first: the tests need the webhooks');
  }
  for (const [key, value] of Object.entries(TEST_TIMINGS)) {
    await setConfig(key, value);
  }
  await refillStock();
}

/**
 * Each run uses up stock of the test products, and an out-of-stock product fails "add to cart". The candidates of
 * findInStockProduct for each currency are refilled before the tests. The specs hard-code no product.
 */
async function refillStock(): Promise<void> {
  const currencies: Currency[] = ['EUR', 'USD'];
  const codes: string[] = [];
  for (const currency of currencies) {
    codes.push(...(await findProductCandidates(currency)).map((p) => p.code));
  }
  const changes = await refillTestStock(codes);
  console.log(`Test stock: ${new Set(codes).size} product(s) checked, ${changes.length} stock level(s) refilled`);
  changes.forEach((line) => console.log(`  ${line}`));
}
