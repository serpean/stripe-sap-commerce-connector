import { expect, test } from '@playwright/test';
import { expectOneAuthorizedOrder } from '../support/assertions';
import { getOrderCodesForPaymentIntent, getPaymentIntentRecord, getPaymentIntentRecordsOfCart, getWebhookEvents, waitFor } from '../support/backend';
import { CARDS, checkoutToPayment, payWithCard } from '../support/checkout';
import { placeOrderByOcc } from '../support/occ';
import { findEvents, triggerResend } from '../support/stripe';

const GRACE_MS = 20_000;

test('9. the customer return and the webhook arrive at the same time: one order', async ({ page }) => {
  const session = await checkoutToPayment(page, { currency: 'USD' });
  const [record] = await getPaymentIntentRecordsOfCart(session.cart.code);

  // The browser pays but its order placement never reaches the server.
  await page.route(/\/users\/[^/]+\/orders\?/, (route) =>
    route.request().method() === 'POST' ? route.abort() : route.continue()
  );
  await payWithCard(page, CARDS.noThreeDs);
  const [event] = await waitFor(async () => {
    const events = await findEvents('payment_intent.amount_capturable_updated', record.paymentIntentId);
    return events.length ? events : undefined;
  }, 60_000, 'the payment_intent.amount_capturable_updated event');

  // At the end of the grace period the webhook task places the order from the server. At the same moment the
  // customer's return places the order by OCC, and Stripe sends the event again.
  const fireAt = event.created * 1000 + GRACE_MS;
  await new Promise((resolve) => setTimeout(resolve, Math.max(0, fireAt - Date.now())));
  const [first, second] = await Promise.all([
    placeOrderByOcc(session.customer, session.cart.code, session.currency),
    placeOrderByOcc(session.customer, session.cart.code, session.currency),
    triggerResend(event.id),
  ]);

  // Every answer is an order or a stable error; never a server error.
  for (const answer of [first, second]) {
    expect(answer.status, JSON.stringify(answer.body)).toBeLessThan(500);
    if (answer.status !== 201) {
      expect(answer.body.errors[0].type).toMatch(/StripePaymentError|CartError/);
    }
  }

  // Exactly one order for the PaymentIntent, with one accepted authorization.
  const [orderCode] = await waitFor(async () => {
    const codes = await getOrderCodesForPaymentIntent(record.paymentIntentId);
    return codes.length ? codes : undefined;
  }, 90_000, 'the order');
  await new Promise((resolve) => setTimeout(resolve, 15_000));
  expect(await getOrderCodesForPaymentIntent(record.paymentIntentId)).toEqual([orderCode]);
  await expectOneAuthorizedOrder(orderCode);
  expect(await getPaymentIntentRecord(record.paymentIntentId)).toMatchObject({ orderCode, state: 'AUTHORIZED' });

  // The event was stored once and processed. When the OCC placement holds the lock first, the webhook task retries
  // 30 s later and then finds the order.
  const stored = await waitFor(async () => {
    const events = await getWebhookEvents(event.id);
    return events.length !== 1 || events[0].state === 'PROCESSED' ? events : undefined;
  }, 60_000, 'the processed webhook event');
  expect(stored).toHaveLength(1);
  expect(stored[0].state).toBe('PROCESSED');
});
