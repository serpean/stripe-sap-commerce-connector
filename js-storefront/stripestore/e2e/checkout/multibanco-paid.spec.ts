import { expect, test } from '@playwright/test';
import { getAvailableStock, getOrder, getPaymentEntries, getSapOrder, setConfig, shipOrder, waitFor, waitForOrderStatus, waitForPaymentEntry } from '../support/backend';
import { checkoutToPayment, expectOrderConfirmation, expectPaymentInstructions, openOrderDetails, payWithMultibanco } from '../support/checkout';
import { minor, netCaptured, retrievePaymentIntent } from '../support/stripe';

test.describe('Multibanco', () => {
  test.afterEach(async () => {
    await setConfig('stripe.payment.pending.timeout.multibanco.seconds', '120');
  });

  test('15. Multibanco paid: the order waits in PAYMENT_PENDING with the voucher, then it is accepted and can ship', async ({ page }) => {
    // Stripe test email without a prefix: the payment arrives after about 3 minutes. The connector timeout is longer.
    test.setTimeout(900_000);
    await setConfig('stripe.payment.pending.timeout.multibanco.seconds', '900');
    const session = await checkoutToPayment(page, { currency: 'EUR' });
    const stockBefore = await getAvailableStock(session.product.code);
    await payWithMultibanco(page, 'e2e-multibanco@example.com');
    const orderCode = await expectOrderConfirmation(page);

    // The order exists with the voucher data in OCC, but no money has arrived: no payment authorization, no sourcing.
    const pending = await waitForOrderStatus(orderCode, 'PAYMENT_PENDING', 30_000);
    const info = (await getOrder(session.customer, orderCode)).stripePaymentInfo;
    expect(info.paymentStatus).toBe('PENDING');
    expect(info.instructionsUrl).toMatch(/^https:\/\//);
    expect(info.paymentReference).toMatch(/^\d+ \d+/);
    expect(info.instructionsExpireAt).toBeTruthy();
    expect((await getPaymentEntries(orderCode)).filter((e) => e.type === 'AUTHORIZATION')).toEqual([]);
    expect(await getAvailableStock(session.product.code)).toBe(stockBefore);
    const open = await retrievePaymentIntent(pending.paymentIntentId!);
    expect(open.status).toBe('requires_action');
    expect(open.amount).toBe(minor(pending.totalPrice));

    // Stripe simulates the payment (about 3 minutes): the order is accepted and sourced.
    await waitFor(async () => (await retrievePaymentIntent(open.id)).status === 'succeeded', 480_000, 'the Multibanco payment', 10_000);
    const ready = await waitForOrderStatus(orderCode, 'READY', 240_000);
    const entries = await waitForPaymentEntry(orderCode, 'AUTHORIZATION');
    expect.soft(entries.filter((e) => e.type === 'AUTHORIZATION').map((e) => [e.status, e.requestId])).toEqual([['ACCEPTED', open.id]]);
    await waitFor(
      async () => (await getOrder(session.customer, orderCode)).stripePaymentInfo?.paymentStatus === 'ACCEPTED',
      180_000,
      'paymentStatus ACCEPTED in OCC',
      5_000
    );

    await shipOrder(orderCode);
    await waitForOrderStatus(orderCode, 'COMPLETED');
    const paid = await retrievePaymentIntent(open.id);
    expect(netCaptured(paid)).toBe(minor(ready.totalPrice));
    expect((await getSapOrder(orderCode))?.status).toBe('COMPLETED');
  });

  // KNOWN GAPS (product, found by this task): the voucher instructions component (cx-stripe-payment-instructions)
  // does not render (1) on the order confirmation page, which has no cx-order-overview (it uses
  // cx-order-confirmation-shipping), and (2) on the order details page after a fresh page load, probably because the
  // outlet is registered only when the lazy feature module loads. OCC returns the data. test.fail makes these tests
  // pass now and fail (a reminder to remove test.fail) once the component shows.
  test('15b. the order confirmation page shows the voucher instructions', async ({ page }) => {
    test.fail(true, 'the instructions outlet is not on the order confirmation page');
    await checkoutToPayment(page, { currency: 'EUR' });
    await payWithMultibanco(page, 'e2e-fill_never@example.com');
    await expectOrderConfirmation(page);
    await expect(page.locator('cx-stripe-payment-instructions')).toBeVisible({ timeout: 15_000 });
  });

  test('15c. the order details page shows the voucher instructions', async ({ page }) => {
    test.fail(true, 'the instructions outlet does not render on order details after a fresh page load');
    const session = await checkoutToPayment(page, { currency: 'EUR' });
    await payWithMultibanco(page, 'e2e-fill_never@example.com');
    const orderCode = await expectOrderConfirmation(page);
    await openOrderDetails(page, orderCode, session.currency);
    await expectPaymentInstructions(page);
  });
});
