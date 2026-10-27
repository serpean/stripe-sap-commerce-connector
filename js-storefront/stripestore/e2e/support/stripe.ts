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

export interface CashBalance {
  available?: Record<string, number> | null;
}

/** The cash balance of the Stripe customer (bank transfers that no PaymentIntent has used yet), by currency in the minor unit. */
export async function getCashBalance(customerId: string): Promise<Record<string, number>> {
  return (await stripeGet(`/customers/${customerId}/cash_balance`) as CashBalance).available ?? {};
}

/** The reference of the bank transfer instructions of a PaymentIntent in AWAITING_FUNDS (requires_action). */
export async function bankTransferReference(paymentIntentId: string): Promise<string> {
  const intent = await stripeGet(`/payment_intents/${paymentIntentId}`);
  const reference = intent.next_action?.display_bank_transfer_instructions?.reference;
  if (!reference) {
    throw new Error(`PaymentIntent ${paymentIntentId} has no bank transfer reference (status ${intent.status})`);
  }
  return reference;
}

/**
 * Simulates an incoming bank transfer (docs.stripe.com/payments/bank-transfers/accept-a-payment, "Test your
 * integration"): POST /v1/test_helpers/customers/{id}/fund_cash_balance. The reference matches the funds to the open
 * PaymentIntent. The currency is EUR (EU bank transfer).
 */
export async function fundCashBalance(customerId: string, amountMinor: number, reference: string): Promise<void> {
  await stripePost(`/test_helpers/customers/${customerId}/fund_cash_balance`, {
    amount: String(amountMinor),
    currency: 'eur',
    reference,
  });
}

export interface CustomerBalanceRefund {
  id: string;
  amount: number;
  status: string;
  origin?: string | null;
  customer?: string | null;
}

/** The refunds with origin customer_balance (funds returned to the customer's bank) created after the time (epoch s). */
export async function listCustomerBalanceRefunds(customerId: string, createdAfter: number): Promise<CustomerBalanceRefund[]> {
  const refunds: CustomerBalanceRefund[] = (await stripeGet('/refunds', { limit: '100', 'created[gte]': String(createdAfter) })).data;
  return refunds.filter((r) => r.origin === 'customer_balance' && (!r.customer || r.customer === customerId));
}

/** The cash balance transactions of the customer, newest first (funded, applied_to_payment, return_initiated...). */
export async function listCashBalanceTransactions(customerId: string): Promise<{ id: string; type: string; net_amount: number }[]> {
  return (await stripeGet(`/customers/${customerId}/cash_balance_transactions`, { limit: '100' })).data;
}

/**
 * Gives the Stripe customer the email of the Stripe account. A cash balance refund (origin=customer_balance) makes
 * Stripe email the customer to collect the bank details, and a Stripe test account can only send email to its own
 * verified address ("Cannot send email ... verify the email address first"). The test customers have example.com
 * addresses, so the refund checks use the account email. The email is never printed.
 */
export async function useAccountEmailForRefunds(customerId: string): Promise<void> {
  const account = await stripeGet('/account');
  if (!account.email) {
    throw new Error('The Stripe account has no email: cash balance refunds cannot be tested');
  }
  await stripePost(`/customers/${customerId}`, { email: account.email });
}
