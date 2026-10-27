import { expect, test } from '@playwright/test';
import { getAvailableStock, getOrder, getPaymentEntries, getSapOrder, setConfig, shipOrder, waitFor, waitForOrderStatus, waitForPaymentEntry } from '../support/backend';
import { TEST_TIMINGS } from '../global-setup';
import { checkoutToPayment, expectOrderConfirmation, expectPaymentInstructions, openOrderDetails, payWithMultibanco } from '../support/checkout';
import { minor, netCaptured, retrievePaymentIntent } from '../support/stripe';

const TIMEOUT_KEY = 'stripe.payment.pending.timeout.multibanco.seconds';

test.describe('Multibanco', () => {
  test.afterEach(async () => {
    await setConfig(TIMEOUT_KEY, TEST_TIMINGS[TIMEOUT_KEY]);
  });

  test('15. Multibanco paid: the order waits in PAYMENT_PENDING with the voucher, then it is accepted and can ship', async ({ page }) => {
    // Stripe test email without a prefix: the payment arrives after about 3 minutes. The connector timeout is longer.
    test.setTimeout(900_000);
    await setConfig(TIMEOUT_KEY, '900');
    const session = await checkoutToPayment(page, { currency: 'EUR' });
    const stockBefore = await getAvailableStock(session.product.code);
    await payWithMultibanco(page, 'e2e-multibanco@example.com');
    const orderCode = await expectOrderConfirmation(page);
    await expectPaymentInstructions(page);

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

  test('15b. the order confirmation page shows the voucher instructions', async ({ page }) => {
    await checkoutToPayment(page, { currency: 'EUR' });
    await payWithMultibanco(page, 'e2e-fill_never@example.com');
    await expectOrderConfirmation(page);
    const { reference } = await expectPaymentInstructions(page);
    expect(reference).toMatch(/^\d+ \d+/);
  });

  test('15c. the order details page shows the voucher instructions after a fresh page load', async ({ page }) => {
    const session = await checkoutToPayment(page, { currency: 'EUR' });
    await payWithMultibanco(page, 'e2e-fill_never@example.com');
    const orderCode = await expectOrderConfirmation(page);
    // page.goto is a full page load: the lazy payment feature has not been loaded in this page.
    await openOrderDetails(page, orderCode, session.currency);
    const { reference } = await expectPaymentInstructions(page);
    expect(reference).toMatch(/^\d+ \d+/);
  });
});
