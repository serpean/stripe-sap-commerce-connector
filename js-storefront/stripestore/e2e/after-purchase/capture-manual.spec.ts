import { expect, test } from '@playwright/test';
import { expectOneAuthorizedOrder } from '../support/assertions';
import { cancelOrder, getSapOrder, shipOrder, waitForOrderStatus, waitForPaymentEntry } from '../support/backend';
import { placeOrderWithCard } from '../support/checkout';
import { minor, retrievePaymentIntent } from '../support/stripe';

test('11. manual mode: the capture happens when the order ships; CAPTURE accepted; PaymentIntent succeeded', async ({ page }) => {
  const { orderCode } = await placeOrderWithCard(page, { currency: 'USD' });
  const { order, intent } = await expectOneAuthorizedOrder(orderCode);
  expect(intent.status).toBe('requires_capture');

  await waitForOrderStatus(orderCode, 'READY');
  await shipOrder(orderCode);

  const entries = await waitForPaymentEntry(orderCode, 'CAPTURE');
  expect(entries.map((e) => [e.type, e.status])).toEqual([
    ['AUTHORIZATION', 'ACCEPTED'],
    ['CAPTURE', 'ACCEPTED'],
  ]);
  expect(entries[1].amount).toBeCloseTo(order.totalPrice, 2);
  await waitForOrderStatus(orderCode, 'COMPLETED');

  const captured = await retrievePaymentIntent(intent.id);
  expect(captured.status).toBe('succeeded');
  expect(captured.amount_received).toBe(minor(order.totalPrice));
});

test('11 (partial cancel first). manual mode: the capture takes the current total, not the authorized amount', async ({ page }) => {
  const { orderCode } = await placeOrderWithCard(page, { currency: 'USD', quantity: 2 });
  const { order: placed, intent } = await expectOneAuthorizedOrder(orderCode);

  await waitForOrderStatus(orderCode, 'READY');
  await cancelOrder(orderCode, { entryNumber: 0, quantity: 1 });
  const reduced = await (async () => {
    for (let i = 0; i < 30; i++) {
      const current = await getSapOrder(orderCode);
      if (current && current.totalPrice < placed.totalPrice) {
        return current;
      }
      await new Promise((resolve) => setTimeout(resolve, 2_000));
    }
    throw new Error(`Order ${orderCode} total did not go down after the partial cancel`);
  })();

  // The reservation stays: nothing is captured or released before the shipping.
  expect((await retrievePaymentIntent(intent.id)).status).toBe('requires_capture');

  await shipOrder(orderCode);
  // A capture of less than the authorized amount is a PARTIAL_CAPTURE.
  const entries = await waitForPaymentEntry(orderCode, /CAPTURE$/);
  const capture = entries.find((e) => /CAPTURE$/.test(e.type))!;
  expect(capture.status).toBe('ACCEPTED');
  expect(capture.amount).toBeCloseTo(reduced.totalPrice, 2);

  const captured = await retrievePaymentIntent(intent.id);
  expect(captured.status).toBe('succeeded');
  expect(captured.amount_received).toBe(minor(reduced.totalPrice));
  expect(captured.amount_received).toBeLessThan(minor(placed.totalPrice));
});
