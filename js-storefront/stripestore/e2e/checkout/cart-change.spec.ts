import { expect, test } from '@playwright/test';
import { expectOneAuthorizedOrder } from '../support/assertions';
import {
  getOrderCodesOfCustomer,
  getPaymentIntentRecord,
  getPaymentIntentRecordsOfCart,
  waitFor,
} from '../support/backend';
import {
  CARDS,
  checkoutToPayment,
  expectOrderConfirmation,
  expectPaymentStepReady,
  MESSAGES,
  paymentElement,
  paymentError,
  payWithBancontact,
  payWithCard,
  waitForPlaceOrder,
} from '../support/checkout';
import { getCart, updateCartEntry } from '../support/occ';
import { listRefunds, minor, netCaptured, retrievePaymentIntent } from '../support/stripe';

test('5. the cart changes after the PaymentIntent is created: the amount is synced before the confirmation', async ({ page }) => {
  const session = await checkoutToPayment(page, { currency: 'USD' });
  const [created] = await getPaymentIntentRecordsOfCart(session.cart.code);
  const amountBefore = (await retrievePaymentIntent(created.paymentIntentId)).amount;

  // Another tab adds a second unit; the payment page does not know.
  await updateCartEntry(session.customer, session.cart.code, 0, 2, session.currency);
  const cart = await getCart(session.customer, session.cart.code, session.currency);
  const newTotal = cart!.totalPriceWithTax!.value;
  expect(minor(newTotal)).toBeGreaterThan(amountBefore);

  await payWithCard(page, CARDS.noThreeDs);
  const orderCode = await expectOrderConfirmation(page);

  // One PaymentIntent for the cart, updated (not replaced), and the order amount equals the amount reserved.
  const { order, intent } = await expectOneAuthorizedOrder(orderCode);
  expect(intent.id).toBe(created.paymentIntentId);
  expect(order.totalPrice).toBeCloseTo(newTotal, 2);
  expect(intent.amount_capturable).toBe(minor(newTotal));
  expect(await getPaymentIntentRecordsOfCart(session.cart.code)).toHaveLength(1);
  expect((await getPaymentIntentRecord(intent.id))?.amount).toBe(minor(newTotal));
});

test('5 (variant). the cart changes during the payment: the money is returned and the customer pays again', async ({ page }) => {
  const session = await checkoutToPayment(page, { currency: 'EUR' });

  // The customer is on the Bancontact page when the cart changes in another tab.
  const placement = waitForPlaceOrder(page);
  await payWithBancontact(page, 'authorize', true, () =>
    updateCartEntry(session.customer, session.cart.code, 0, 2, session.currency)
  );

  // Back in the shop: no order; OCC gives the stable error code and the shop shows its warning.
  const response = await placement;
  expect(response.status).toBe(400);
  expect(response.body.errors[0]).toMatchObject({ type: 'StripePaymentError', reason: 'STRIPE_AMOUNT_MISMATCH' });
  await expect(page).toHaveURL(/checkout\/payment-details/);
  await expectPaymentStepReady(page);
  await expect(paymentError(page)).toHaveText(MESSAGES.amountChanged);
  expect(await getOrderCodesOfCustomer(session.customer)).toEqual([]);

  // Stripe: the first payment (Bancontact is charged at once) is refunded completely.
  const [first] = await getPaymentIntentRecordsOfCart(session.cart.code);
  const firstIntent = await waitFor(async () => {
    const live = await retrievePaymentIntent(first.paymentIntentId);
    return live.status === 'succeeded' && netCaptured(live) === 0 ? live : undefined;
  }, 60_000, 'the refund of the first payment');
  const refunds = await listRefunds(firstIntent.id);
  expect(refunds.reduce((sum, r) => sum + r.amount, 0)).toBe(firstIntent.amount_received);
  expect((await getPaymentIntentRecord(firstIntent.id))?.state).toBe('REFUNDED');

  // The customer pays again the new total and gets one order.
  const cart = await getCart(session.customer, session.cart.code, session.currency);
  const newTotal = cart!.totalPriceWithTax!.value;
  // The shop mounted a new Payment Element for the new total: the customer pays again without a reload.
  await expect(paymentElement(page).locator('input').first()).toBeAttached({ timeout: 30_000 });
  await payWithBancontact(page, 'authorize', true);
  const orderCode = await expectOrderConfirmation(page);
  const { order, intent } = await expectOneAuthorizedOrder(orderCode);
  expect(intent.id).not.toBe(firstIntent.id);
  expect(order.totalPrice).toBeCloseTo(newTotal, 2);
  expect(intent.amount_received).toBe(minor(newTotal));
  expect(await getOrderCodesOfCustomer(session.customer)).toEqual([orderCode]);
});
