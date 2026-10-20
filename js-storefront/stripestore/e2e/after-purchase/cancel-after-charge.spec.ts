import { expect, test } from '@playwright/test';
import { expectOneAuthorizedOrder } from '../support/assertions';
import { cancelOrder, waitForOrderStatus, waitForPaymentEntry } from '../support/backend';
import { placeOrderWithBancontact } from '../support/checkout';
import { listRefunds, netCaptured, retrievePaymentIntent } from '../support/stripe';

test('17. cancel with the money already charged (Bancontact): full refund; refund entry accepted; order cancelled', async ({ page }) => {
  const { orderCode } = await placeOrderWithBancontact(page);
  const { intent } = await expectOneAuthorizedOrder(orderCode);
  expect(intent.status).toBe('succeeded');

  await waitForOrderStatus(orderCode, 'READY');
  await cancelOrder(orderCode);
  await waitForOrderStatus(orderCode, 'CANCELLED');

  // Target 0: everything is refunded, the delivery cost included.
  const entries = await waitForPaymentEntry(orderCode, 'REFUND_FOLLOW_ON');
  const refunds = entries.filter((e) => e.type === 'REFUND_FOLLOW_ON');
  expect(refunds.map((e) => e.status)).toEqual(['ACCEPTED']);
  expect(Math.round(refunds[0].amount * 100)).toBe(intent.amount_received);

  const live = await retrievePaymentIntent(intent.id);
  expect(netCaptured(live)).toBe(0);
  const stripeRefunds = await listRefunds(intent.id);
  expect(stripeRefunds.reduce((sum, r) => sum + r.amount, 0)).toBe(intent.amount_received);
});
