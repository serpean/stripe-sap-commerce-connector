import { expect, test } from '@playwright/test';
import { getOrder, getPaymentEntries, waitFor, waitForOrderStatus } from '../support/backend';
import { checkoutToPayment, expectOrderConfirmation, payWithMultibanco } from '../support/checkout';
import { minor, netCaptured, retrievePaymentIntent } from '../support/stripe';

test('16. Multibanco not paid: at the connector timeout the order is cancelled and the PaymentIntent too', async ({ page }) => {
  test.setTimeout(600_000);
  const session = await checkoutToPayment(page, { currency: 'EUR' });
  // fill_never: Stripe never pays the voucher and fails it only after 11 days. The connector timeout (120 s) acts first.
  await payWithMultibanco(page, 'e2e-fill_never@example.com');
  const orderCode = await expectOrderConfirmation(page);
  const pending = await waitForOrderStatus(orderCode, 'PAYMENT_PENDING', 30_000);
  const open = await retrievePaymentIntent(pending.paymentIntentId!);
  expect(open.status).toBe('requires_action');
  expect(open.amount).toBe(minor(pending.totalPrice));

  await waitForOrderStatus(orderCode, 'CANCELLED', 360_000);
  const cancelled = await waitFor(async () => {
    const live = await retrievePaymentIntent(open.id);
    return live.status === 'canceled' ? live : undefined;
  }, 120_000, 'the PaymentIntent canceled');
  expect(netCaptured(cancelled)).toBe(0);
  expect((await getPaymentEntries(orderCode)).filter((e) => e.type === 'AUTHORIZATION' && e.status === 'ACCEPTED')).toEqual([]);
  await waitFor(
    async () => (await getOrder(session.customer, orderCode)).stripePaymentInfo?.paymentStatus !== 'PENDING',
    120_000,
    'the OCC order without the pending payment status',
    5_000
  );
});

test('17. Multibanco voucher expired at Stripe: the order is cancelled and no money is taken', async ({ page }) => {
  test.setTimeout(600_000);
  const session = await checkoutToPayment(page, { currency: 'EUR' });
  // expire_immediately: Stripe fails the voucher within seconds (payment_intent.payment_failed).
  await payWithMultibanco(page, 'e2e-expire_immediately@example.com');
  const orderCode = await expectOrderConfirmation(page);
  const order = await waitFor(async () => {
    const live = await waitForOrderStatus(orderCode, 'PAYMENT_PENDING', 5_000).catch(() => undefined);
    return live ?? (await waitForOrderStatus(orderCode, 'CANCELLED', 5_000).catch(() => undefined));
  }, 60_000, 'the order after the confirmation');

  await waitForOrderStatus(orderCode, 'CANCELLED', 300_000);
  const intent = await retrievePaymentIntent(order.paymentIntentId!);
  expect(['requires_payment_method', 'canceled']).toContain(intent.status);
  expect(netCaptured(intent)).toBe(0);
  expect((await getPaymentEntries(orderCode)).filter((e) => e.type === 'AUTHORIZATION' && e.status === 'ACCEPTED')).toEqual([]);
  expect(session.customer.email).toBeTruthy();
});
