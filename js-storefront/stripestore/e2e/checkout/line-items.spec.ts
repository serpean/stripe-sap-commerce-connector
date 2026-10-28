import { expect, test } from '@playwright/test';
import { expectOneAuthorizedOrder } from '../support/assertions';
import { CARDS, checkoutToPayment, expectOrderConfirmation, payWithCard } from '../support/checkout';
import { getOrder } from '../support/backend';
import { minor, retrievePaymentIntentWithLineItems, stripePost } from '../support/stripe';

test('16. line items: one line per cart entry and the lines add up to the amount', async ({ page }) => {
  const session = await checkoutToPayment(page, { currency: 'USD', quantity: 2 });
  await payWithCard(page, CARDS.noThreeDs);
  const orderCode = await expectOrderConfirmation(page);
  const { order, intent } = await expectOneAuthorizedOrder(orderCode);

  const live = await retrievePaymentIntentWithLineItems(intent.id);
  const details = live.amount_details!;
  const lines = details.line_items!.data;
  const occOrder = await getOrder(session.customer, orderCode);
  expect(lines).toHaveLength(occOrder.entries.length);
  expect(lines[0].quantity).toBe(2);
  const sum =
    lines.reduce((total, line) => total + line.unit_cost * line.quantity, 0) -
    (details.discount_amount ?? 0) +
    (details.shipping?.amount ?? 0) +
    (details.tax?.total_tax_amount ?? 0);
  expect(sum).toBe(live.amount);
  expect(live.amount).toBe(minor(order.totalPrice));
});

test('16b. Stripe API check: an update with amount_details cleared works on a PaymentIntent that never had line items', async () => {
  // A check of Stripe, not of the shop: the connector sends amount_details="" in an update without line items, also
  // for a PaymentIntent without them. The PaymentIntent is cancelled even when an assertion fails.
  const created = await stripePost('/payment_intents', {
    amount: '2000',
    currency: 'eur',
    'automatic_payment_methods[enabled]': 'true',
  });
  try {
    expect(created.amount_details?.line_items?.data ?? []).toEqual([]);
    const updated = await stripePost(`/payment_intents/${created.id}`, { amount: '2500', amount_details: '' });
    expect(updated.amount).toBe(2500);
  } finally {
    await stripePost(`/payment_intents/${created.id}/cancel`, {});
  }
});
