import { expect, test } from '@playwright/test';
import {
  getAvailableStock,
  getOrder,
  getPaymentEntries,
  shipOrder,
  waitFor,
  waitForOrderStatus,
  waitForPaymentEntry,
} from '../support/backend';
import { checkoutToPayment, expectOrderConfirmation, payWithSepa, SEPA_IBANS } from '../support/checkout';
import { minor, netCaptured, retrievePaymentIntent } from '../support/stripe';

test('14. SEPA Direct Debit: the order waits in PAYMENT_PENDING, then it is accepted and can ship', async ({ page }) => {
  const session = await checkoutToPayment(page, { currency: 'EUR' });
  const stockBefore = await getAvailableStock(session.product.code);
  await payWithSepa(page, SEPA_IBANS.succeedsAfterDelay);
  const orderCode = await expectOrderConfirmation(page);

  // The debit takes time: the order exists, but the money has not arrived. No payment authorization, no sourcing.
  // The status is set a moment after the confirmation page: wait for it.
  const pending = await waitForOrderStatus(orderCode, 'PAYMENT_PENDING', 30_000);
  expect(pending.status).toBe('PAYMENT_PENDING');
  expect((await getOrder(session.customer, orderCode)).stripePaymentInfo?.paymentStatus).toBe('PENDING');
  expect((await getPaymentEntries(orderCode)).filter((e) => e.type === 'AUTHORIZATION')).toEqual([]);
  expect(await getAvailableStock(session.product.code)).toBe(stockBefore);
  const processing = await retrievePaymentIntent(pending.paymentIntentId!);
  expect(processing.status).toBe('processing');
  expect(processing.amount).toBe(minor(pending.totalPrice));

  // Stripe confirms the debit (a few minutes in test mode): the order is accepted and sourced.
  await waitFor(async () => (await retrievePaymentIntent(processing.id)).status === 'succeeded', 480_000, 'the SEPA debit', 10_000);
  const ready = await waitForOrderStatus(orderCode, 'READY', 240_000);
  const entries = await waitForPaymentEntry(orderCode, 'AUTHORIZATION');
  // Soft: a duplicate entry must fail the test, but the rest of the flow is still checked.
  expect.soft(entries.filter((e) => e.type === 'AUTHORIZATION').map((e) => [e.status, e.requestId])).toEqual([['ACCEPTED', processing.id]]);
  // The OCC order read can stay cached for up to a minute after the order changed.
  await waitFor(
    async () => (await getOrder(session.customer, orderCode)).stripePaymentInfo?.paymentStatus === 'ACCEPTED',
    180_000,
    'paymentStatus ACCEPTED in OCC',
    5_000
  );

  await shipOrder(orderCode);
  await waitForOrderStatus(orderCode, 'COMPLETED');
  const paid = await retrievePaymentIntent(processing.id);
  expect(paid.status).toBe('succeeded');
  expect(netCaptured(paid)).toBe(minor(ready.totalPrice));
});
