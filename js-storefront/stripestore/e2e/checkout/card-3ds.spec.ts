import { expect, test } from '@playwright/test';
import { getPaymentIntentRecord, getPaymentIntentRecordsOfCart, waitFor } from '../support/backend';
import { retrievePaymentIntent } from '../support/stripe';
import { expectNoOrderAndCartIntact, expectOneAuthorizedOrder } from '../support/assertions';
import {
  CARDS,
  checkoutToPayment,
  completeThreeDs,
  expectOrderConfirmation,
  expectPaymentStepReady,
  payWithCard,
} from '../support/checkout';

test('2. card with 3DS completed: order created', async ({ page }) => {
  await checkoutToPayment(page, { currency: 'EUR' });
  await payWithCard(page, CARDS.threeDs);
  await completeThreeDs(page, 'complete');

  const orderCode = await expectOrderConfirmation(page);
  const { order, intent } = await expectOneAuthorizedOrder(orderCode);
  expect(intent.status).toBe('requires_capture');
  expect((await getPaymentIntentRecord(intent.id))?.state).toBe('AUTHORIZED');
  expect(order.currency).toBe('EUR');
});

test('3. card with 3DS failed: no order, the customer stays on the payment step, the cart is intact', async ({ page }) => {
  const session = await checkoutToPayment(page, { currency: 'EUR' });
  await payWithCard(page, CARDS.threeDs);
  await completeThreeDs(page, 'fail');

  // Stripe: the authentication failed and the PaymentIntent waits for a new payment method.
  const [record] = await getPaymentIntentRecordsOfCart(session.cart.code);
  const intent = await waitFor(async () => {
    const live = await retrievePaymentIntent(record.paymentIntentId);
    return live.last_payment_error ? live : undefined;
  }, 30_000, 'the failed 3DS on the PaymentIntent');
  expect(intent.status).toBe('requires_payment_method');
  expect(intent.last_payment_error?.code).toMatch(/authentication/);
  await expect(page).toHaveURL(/checkout\/payment-details/);
  await expectNoOrderAndCartIntact(session);
});

test('3 (UI). after a failed 3DS the shop shows its error and the customer can pay again', async ({ page }) => {
  await checkoutToPayment(page, { currency: 'EUR' });
  await payWithCard(page, CARDS.threeDs);
  await completeThreeDs(page, 'fail');
  await expectPaymentStepReady(page);
});
