import { execFileSync } from 'node:child_process';
import { setConfig } from './support/backend';

/** Short connector timings for the tests. They change the running server only; a restart reads local.properties. */
export const TEST_TIMINGS: Record<string, string> = {
  'stripe.webhook.graceperiod.seconds': '20',
  'stripe.payment.pending.retry.seconds': '10',
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
}
