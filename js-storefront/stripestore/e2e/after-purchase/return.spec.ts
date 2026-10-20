import { expect, test } from '@playwright/test';
import { expectOneAuthorizedOrder } from '../support/assertions';
import { createReturn, getSapOrder, shipOrder, waitFor, waitForOrderStatus, waitForPaymentEntry } from '../support/backend';
import { placeOrderWithCard } from '../support/checkout';
import { listRefunds, minor, retrievePaymentIntent } from '../support/stripe';

test('14. return: refund in Stripe; REFUND_FOLLOW_ON entry accepted', async ({ page }) => {
  const { orderCode } = await placeOrderWithCard(page, { currency: 'USD' });
  const { order, intent } = await expectOneAuthorizedOrder(orderCode);
  await waitForOrderStatus(orderCode, 'READY');
  await shipOrder(orderCode);
  await waitForPaymentEntry(orderCode, 'CAPTURE');
  await waitForOrderStatus(orderCode, 'COMPLETED');

  await createReturn(orderCode);

  // The return refunds the products; the delivery cost is not refunded (refundDeliveryCost = false).
  const expectedRefund = minor(order.totalPrice - order.deliveryCost);
  const entries = await waitForPaymentEntry(orderCode, 'REFUND_FOLLOW_ON');
  const refundEntry = entries.find((e) => e.type === 'REFUND_FOLLOW_ON')!;
  expect(refundEntry.status).toBe('ACCEPTED');
  expect(minor(refundEntry.amount)).toBe(expectedRefund);

  const refunds = await waitFor(async () => {
    const list = await listRefunds(intent.id);
    return list.length ? list : undefined;
  }, 60_000, 'the Stripe refund');
  expect(refunds.map((r) => [r.amount, r.status])).toEqual([[expectedRefund, 'succeeded']]);
  const live = await retrievePaymentIntent(intent.id);
  expect(live.latest_charge!.amount_refunded).toBe(expectedRefund);
  expect((await getSapOrder(orderCode))?.code).toBe(orderCode);
});
