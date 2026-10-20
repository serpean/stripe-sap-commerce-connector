import { expect, test } from '@playwright/test';
import { getOrderCodesOfCustomer, getPaymentIntentRecord, getPaymentIntentRecordsOfCart, getWebhookEvents, waitFor } from '../support/backend';
import { checkoutToPayment, payWithBancontact } from '../support/checkout';
import { getCart, updateCartEntry } from '../support/occ';
import { findEvents, listRefunds, netCaptured, retrievePaymentIntent } from '../support/stripe';

test('10. the amount does not match when the webhook arrives: no order, the money is returned', async ({ browser }) => {
  const page = await browser.newPage();
  const session = await checkoutToPayment(page, { currency: 'EUR' });
  const [record] = await getPaymentIntentRecordsOfCart(session.cart.code);

  // The customer pays and does not come back; then the cart changes (another tab) before the webhook places the order.
  await payWithBancontact(page, 'authorize', false);
  await updateCartEntry(session.customer, session.cart.code, 0, 2, session.currency);

  // After the grace period the webhook finds the mismatch and refunds everything.
  const intent = await waitFor(async () => {
    const live = await retrievePaymentIntent(record.paymentIntentId);
    return live.status === 'succeeded' && live.amount_received > 0 && netCaptured(live) === 0 ? live : undefined;
  }, 120_000, 'the refund of the payment');
  const refunds = await listRefunds(intent.id);
  expect(refunds.reduce((sum, r) => sum + r.amount, 0)).toBe(intent.amount_received);

  expect(await getOrderCodesOfCustomer(session.customer)).toEqual([]);
  expect((await getPaymentIntentRecord(intent.id))?.state).toBe('REFUNDED');
  const cart = await getCart(session.customer, session.cart.code, session.currency);
  expect(cart?.entries?.[0].quantity).toBe(2);

  const [succeeded] = await findEvents('payment_intent.succeeded', intent.id);
  const stored = await waitFor(async () => {
    const events = await getWebhookEvents(succeeded.id);
    return events[0]?.state === 'PROCESSED' ? events : undefined;
  }, 60_000, 'the processed webhook event');
  expect(stored).toHaveLength(1);
});
