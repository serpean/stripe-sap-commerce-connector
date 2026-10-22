import { execFile } from 'node:child_process';
import { createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { withApi } from './occ';

/*
 * Stripe state for the e2e tests, read with the REST API. The secret key comes from STRIPE_SECRET_KEY or from the
 * repository .env file. It is never printed and never put on a command line.
 */

const STRIPE_API = 'https://api.stripe.com/v1';
const ENV_FILE = resolve(__dirname, '../../../../.env');

function secretKey(): string {
  const fromEnv = process.env['STRIPE_SECRET_KEY'];
  if (fromEnv) {
    return fromEnv;
  }
  const line = readFileSync(ENV_FILE, 'utf8')
    .split(/\r?\n/)
    .map((l) => l.match(/^\s*(?:export\s+)?STRIPE_SECRET_KEY=(.*)$/))
    .filter((m): m is RegExpMatchArray => !!m)
    .pop();
  const key = line?.[1].trim().replace(/^["']|["']$/g, '');
  if (!key) {
    throw new Error('Set STRIPE_SECRET_KEY (or add it to .env)');
  }
  return key;
}

async function stripeGet(path: string, params: Record<string, string> = {}): Promise<any> {
  return withApi(async (api) => {
    const response = await api.get(`${STRIPE_API}${path}`, {
      headers: { Authorization: `Bearer ${secretKey()}` },
      params,
    });
    const body = await response.json();
    if (!response.ok()) {
      throw new Error(`Stripe GET ${path} failed: ${response.status()} ${body?.error?.message}`);
    }
    return body;
  });
}

export interface PaymentIntent {
  id: string;
  status: string;
  amount: number;
  amount_received: number;
  amount_capturable: number;
  currency: string;
  capture_method: string;
  metadata: Record<string, string>;
  last_payment_error?: { code?: string; type?: string } | null;
  payment_method_types?: string[];
  amount_details?: AmountDetails | null;
  latest_charge?: { id: string; amount: number; amount_captured: number; amount_refunded: number; captured: boolean } | null;
}

export interface AmountDetails {
  discount_amount?: number | null;
  shipping?: { amount?: number | null } | null;
  tax?: { total_tax_amount?: number | null } | null;
  line_items?: { data: { product_name: string; quantity: number; unit_cost: number; product_code?: string | null }[] } | null;
}

/** The PaymentIntent with its amount details and line items (expand[]=amount_details.line_items). */
export function retrievePaymentIntentWithLineItems(id: string): Promise<PaymentIntent> {
  return stripeGet(`/payment_intents/${id}`, { 'expand[]': 'amount_details.line_items' });
}

/** A form POST to Stripe, for the checks that need a PaymentIntent that the connector did not create. */
export async function stripePost(path: string, form: Record<string, string>): Promise<any> {
  return withApi(async (api) => {
    const response = await api.post(`${STRIPE_API}${path}`, { headers: { Authorization: `Bearer ${secretKey()}` }, form });
    const body = await response.json();
    if (!response.ok()) {
      throw new Error(`Stripe POST ${path} failed: ${response.status()} ${body?.error?.message}`);
    }
    return body;
  });
}

export function retrievePaymentIntent(id: string): Promise<PaymentIntent> {
  return stripeGet(`/payment_intents/${id}`, { 'expand[]': 'latest_charge' });
}

export interface Refund {
  id: string;
  amount: number;
  status: string;
  payment_intent: string;
}

export async function listRefunds(paymentIntentId: string): Promise<Refund[]> {
  return (await stripeGet('/refunds', { payment_intent: paymentIntentId, limit: '100' })).data;
}

export interface StripeEvent {
  id: string;
  type: string;
  created: number;
  data: { object: { id: string } };
}

/** The recent events of the type for the PaymentIntent (Stripe cannot filter events by object id). */
export async function findEvents(type: string, paymentIntentId: string): Promise<StripeEvent[]> {
  const events: StripeEvent[] = (await stripeGet('/events', { type, limit: '100' })).data;
  return events.filter((event) => event.data.object.id === paymentIntentId);
}

/** Sends the event again to the webhook endpoints and to the Stripe CLI listener (stripe events resend). */
export async function triggerResend(eventId: string): Promise<void> {
  await runStripeCli(['events', 'resend', eventId]);
}

function runStripeCli(args: string[]): Promise<string> {
  return new Promise((done, fail) => {
    execFile('stripe', args, { env: { ...process.env, STRIPE_API_KEY: secretKey() } }, (error, stdout, stderr) =>
      error ? fail(new Error(`stripe ${args.slice(0, 2).join(' ')} failed: ${stderr}`)) : done(stdout)
    );
  });
}

export function retrieveEvent(id: string): Promise<StripeEvent> {
  return stripeGet(`/events/${id}`);
}

/**
 * Signs a payload as Stripe does (Stripe-Signature header), with the signing secret of the Stripe CLI listener. The
 * server has the same secret in stripe.webhook.secret.
 */
export async function signWebhook(payload: string): Promise<string> {
  const secret = (await runStripeCli(['listen', '--print-secret'])).trim();
  const timestamp = Math.floor(Date.now() / 1000);
  const signature = createHmac('sha256', secret).update(`${timestamp}.${payload}`).digest('hex');
  return `t=${timestamp},v1=${signature}`;
}

/** The net amount the customer paid: captured minus refunded, in the minor unit. */
export function netCaptured(intent: PaymentIntent): number {
  return (intent.amount_received ?? 0) - (intent.latest_charge?.amount_refunded ?? 0);
}

/** Converts an amount of a two-decimal currency (EUR, USD) to the minor unit. */
export function minor(amount: number): number {
  return Math.round(amount * 100);
}
