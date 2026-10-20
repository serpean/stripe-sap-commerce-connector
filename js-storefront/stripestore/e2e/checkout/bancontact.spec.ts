import { expect, test } from '@playwright/test';
import { expectNoOrderAndCartIntact, expectOneAuthorizedOrder } from '../support/assertions';
import { getOrderCodesOfCustomer, getPaymentIntentRecord, getPaymentIntentRecordsOfCart, waitFor } from '../support/backend';
import {
  checkoutToPayment,
  ERROR_CODE_BUG,
  expectOrderConfirmation,
  expectPaymentStepReady,
  payWithBancontact,
  UI_REFRESH_BUG,
  waitForPlaceOrder,
} from '../support/checkout';
import { getCart } from '../support/occ';
import { retrievePaymentIntent } from '../support/stripe';

test('6. Bancontact in EUR, the customer comes back: order created', async ({ page }) => {
  await checkoutToPayment(page, { currency: 'EUR' });
  await payWithBancontact(page, 'authorize', true);

  const orderCode = await expectOrderConfirmation(page);
  const { order, intent } = await expectOneAuthorizedOrder(orderCode);
  expect(order.currency).toBe('EUR');
  // Bancontact cannot reserve money: it is charged at once.
  expect(intent.status).toBe('succeeded');
  expect(intent.amount_received).toBe(intent.amount);
  expect((await getPaymentIntentRecord(intent.id))?.state).toBe('CAPTURED');
});

test('7. Bancontact, the customer pays and does not come back: the webhook creates the order after the grace period', async ({
  browser,
}) => {
  const page = await browser.newPage();
  const session = await checkoutToPayment(page, { currency: 'EUR' });
  const [record] = await getPaymentIntentRecordsOfCart(session.cart.code);
  const paidAt = Date.now();
  await payWithBancontact(page, 'authorize', false);

  // Stripe has the money; SAP has no order yet during the grace period (20 s in the tests).
  const intent = await waitFor(async () => {
    const live = await retrievePaymentIntent(record.paymentIntentId);
    return live.status === 'succeeded' ? live : undefined;
  }, 60_000, 'the Bancontact payment');
  expect(await getOrderCodesOfCustomer(session.customer)).toEqual([]);

  const [orderCode] = await waitFor(async () => {
    const codes = await getOrderCodesOfCustomer(session.customer);
    return codes.length ? codes : undefined;
  }, 120_000, 'the order placed by the webhook');
  expect(Date.now() - paidAt).toBeGreaterThanOrEqual(15_000);

  const placed = await expectOneAuthorizedOrder(orderCode);
  expect(placed.intent.id).toBe(intent.id);
  expect(placed.intent.amount_received).toBe(intent.amount);
  expect(await getPaymentIntentRecord(intent.id)).toMatchObject({ orderCode, state: 'CAPTURED' });
  // The cart became the order.
  expect(await getCart(session.customer, session.cart.code, session.currency)).toBeUndefined();
});

test('8. Bancontact cancelled on the provider page: no order, the cart is intact', async ({ page }) => {
  const session = await checkoutToPayment(page, { currency: 'EUR' });
  const [record] = await getPaymentIntentRecordsOfCart(session.cart.code);
  const placement = waitForPlaceOrder(page);
  await payWithBancontact(page, 'fail', true);

  // The server asks Stripe, sees the failed payment and refuses the order; the customer stays on the payment step.
  expect((await placement).status).toBe(400);
  await expect(page).toHaveURL(/checkout\/payment-details/);
  expect((await retrievePaymentIntent(record.paymentIntentId)).status).toBe('requires_payment_method');
  await expectNoOrderAndCartIntact(session);
});

test('8 (error code). a cancelled Bancontact payment gives the stable Stripe error code', async ({ page }) => {
  test.info().annotations.push(ERROR_CODE_BUG);
  test.fail();
  await checkoutToPayment(page, { currency: 'EUR' });
  const placement = waitForPlaceOrder(page);
  await payWithBancontact(page, 'fail', true);
  expect((await placement).body.errors[0]).toMatchObject({ type: 'StripePaymentError', reason: 'STRIPE_PAYMENT_FAILED' });
});

test('8 (UI). after a cancelled Bancontact payment the shop shows a warning and the customer can pay again', async ({ page }) => {
  test.info().annotations.push(UI_REFRESH_BUG);
  test.fail();
  await checkoutToPayment(page, { currency: 'EUR' });
  await payWithBancontact(page, 'fail', true);
  await expectPaymentStepReady(page);
});
