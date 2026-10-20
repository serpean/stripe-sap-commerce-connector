import { expect, test } from '@playwright/test';
import { expectOneAuthorizedOrder } from '../support/assertions';
import { cancelOrder, getPaymentEntries, waitFor, waitForOrderStatus } from '../support/backend';
import { placeOrderWithCard } from '../support/checkout';
import { retrievePaymentIntent } from '../support/stripe';

test('13. cancel before the capture: void; PaymentIntent canceled (delivery included)', async ({ page }) => {
  const { orderCode } = await placeOrderWithCard(page, { currency: 'USD' });
  const { intent } = await expectOneAuthorizedOrder(orderCode);

  await waitForOrderStatus(orderCode, 'READY');
  await cancelOrder(orderCode);
  await waitForOrderStatus(orderCode, 'CANCELLED');

  const canceled = await waitFor(async () => {
    const live = await retrievePaymentIntent(intent.id);
    return live.status === 'canceled' ? live : undefined;
  }, 60_000, 'the void of the PaymentIntent');
  expect(canceled.amount_received).toBe(0);

  const entries = await getPaymentEntries(orderCode);
  expect(entries.filter((e) => e.type === 'CAPTURE')).toEqual([]);
  expect(entries.filter((e) => e.type === 'CANCEL').map((e) => e.status)).toEqual(['ACCEPTED']);
});
