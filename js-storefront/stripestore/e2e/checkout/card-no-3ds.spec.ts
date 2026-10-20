import { expect, test } from '@playwright/test';
import { getOrder, getOrderCodesForPaymentIntent, getPaymentEntries, getPaymentIntentRecord, getSapOrder } from '../support/backend';
import { CARDS, checkoutToPayment, expectOrderConfirmation, payWithCard } from '../support/checkout';
import { getStripeConfigAnonymously } from '../support/occ';
import { minor, retrievePaymentIntent } from '../support/stripe';

test('1. card without 3DS: order created with an accepted AUTHORIZATION', async ({ page }) => {
  // The storefront reads the publishable key from the backend without a token.
  const config = await getStripeConfigAnonymously();
  expect(config.status).toBe(200);
  expect(config.body.publishableKey).toMatch(/^pk_/);

  const session = await checkoutToPayment(page, { currency: 'USD' });
  await payWithCard(page, CARDS.noThreeDs);

  // UI: POST /orders with the Stripe cart is accepted (no cart.paymentInfoNotSet); the order is in the account.
  const orderCode = await expectOrderConfirmation(page);
  // The reference storefront pages show no payment card; the card data is checked on the OCC order below.
  await page.goto(`/electronics-spa/en/USD/my-account/order/${orderCode}`);
  await expect(page.getByText(orderCode).first()).toBeVisible();

  // SAP: one order with one accepted AUTHORIZATION of the order total.
  const order = await getSapOrder(orderCode);
  expect(order?.currency).toBe('USD');
  const paymentIntentId = order!.paymentIntentId!;
  expect(paymentIntentId).toMatch(/^pi_/);
  expect(await getOrderCodesForPaymentIntent(paymentIntentId)).toEqual([orderCode]);
  const entries = await getPaymentEntries(orderCode);
  expect(entries.map((e) => [e.type, e.status, e.requestId])).toEqual([['AUTHORIZATION', 'ACCEPTED', paymentIntentId]]);
  expect(entries[0].amount).toBeCloseTo(order!.totalPrice, 2);
  const record = await getPaymentIntentRecord(paymentIntentId);
  expect(record).toMatchObject({ state: 'AUTHORIZED', orderCode, cartCode: session.cart.code });

  // OCC: the order carries stripePaymentInfo with fields=FULL.
  const occOrder = await getOrder(session.customer, orderCode);
  expect(occOrder.stripePaymentInfo).toMatchObject({ paymentMethodType: 'card', cardLast4: '4242' });

  // Stripe: the money is reserved (manual capture), for exactly the order total.
  const intent = await retrievePaymentIntent(paymentIntentId);
  expect(intent.status).toBe('requires_capture');
  expect(intent.amount).toBe(minor(order!.totalPrice));
  expect(intent.amount_capturable).toBe(minor(order!.totalPrice));
});
