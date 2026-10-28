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
import { expectOneAuthorizedOrder } from '../support/assertions';
import { minor, netCaptured, retrievePaymentIntent } from '../support/stripe';

test('14. SEPA Direct Debit: the order waits in PAYMENT_PENDING, then it is accepted and can ship', async ({ page }) => {
  // Stripe can take several minutes to settle a test debit (9 min seen); the waits below allow it, the test must too.
  test.setTimeout(900_000);
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
  // On a slow machine the test debit can already be confirmed at this point; the order checks above still ran first.
  expect(['processing', 'succeeded']).toContain(processing.status);
  expect(processing.amount).toBe(minor(pending.totalPrice));

  // Stripe confirms the debit (a few minutes in test mode): the order is accepted and sourced.
  await waitFor(async () => (await retrievePaymentIntent(processing.id)).status === 'succeeded', 480_000, 'the SEPA debit', 10_000);
  const ready = await waitForOrderStatus(orderCode, 'READY', 240_000);
  const entries = await waitForPaymentEntry(orderCode, 'AUTHORIZATION');
  // Exactly one ACCEPTED authorization of this PaymentIntent (the webhook and the order process must not both add one).
  expect(entries.filter((e) => e.type === 'AUTHORIZATION').map((e) => [e.status, e.requestId])).toEqual([['ACCEPTED', processing.id]]);
  await expectOneAuthorizedOrder(orderCode);
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
