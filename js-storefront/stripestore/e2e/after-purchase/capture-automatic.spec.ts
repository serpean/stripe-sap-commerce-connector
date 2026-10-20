import { expect, test } from '@playwright/test';
import { expectOneAuthorizedOrder } from '../support/assertions';
import { setConfig, shipOrder, waitForOrderStatus, waitForPaymentEntry } from '../support/backend';
import { placeOrderWithCard } from '../support/checkout';
import { minor, retrievePaymentIntent } from '../support/stripe';

test.describe('automatic capture mode', () => {
  test.beforeAll(async () => setConfig('stripe.capture.mode', 'automatic'));
  test.afterAll(async () => setConfig('stripe.capture.mode', 'manual'));

  test('12. automatic mode: charged when the order is placed; the SAP capture only records it; no second charge', async ({ page }) => {
    const { orderCode } = await placeOrderWithCard(page, { currency: 'USD' });
    const { order, intent } = await expectOneAuthorizedOrder(orderCode);
    expect(intent.capture_method).toMatch(/^automatic/);
    expect(intent.status).toBe('succeeded');
    expect(intent.amount_received).toBe(minor(order.totalPrice));
    const chargeBefore = intent.latest_charge!.id;

    await waitForOrderStatus(orderCode, 'READY');
    await shipOrder(orderCode);
    const entries = await waitForPaymentEntry(orderCode, 'CAPTURE');
    expect(entries.map((e) => [e.type, e.status])).toEqual([
      ['AUTHORIZATION', 'ACCEPTED'],
      ['CAPTURE', 'ACCEPTED'],
    ]);
    await waitForOrderStatus(orderCode, 'COMPLETED');

    // Stripe: still one charge for the order total.
    const after = await retrievePaymentIntent(intent.id);
    expect(after.amount_received).toBe(minor(order.totalPrice));
    expect(after.latest_charge!.id).toBe(chargeBefore);
    expect(after.latest_charge!.amount_captured).toBe(minor(order.totalPrice));
    expect(after.latest_charge!.amount_refunded).toBe(0);
  });
});
