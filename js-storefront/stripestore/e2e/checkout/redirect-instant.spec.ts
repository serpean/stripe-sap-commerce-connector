import { expect, test } from '@playwright/test';
import { expectOneAuthorizedOrder } from '../support/assertions';
import { getPaymentIntentRecord } from '../support/backend';
import { checkoutToPayment, expectOrderConfirmation, payWithRedirectMethod } from '../support/checkout';
import { minor, netCaptured } from '../support/stripe';

// Bizum has a test simulation in Stripe test mode (the phone number is accepted, no provider page): it is used here.
const METHOD = 'bizum';

test(`13. instant redirect method (${METHOD}): the order is charged at once`, async ({ page }) => {
  await checkoutToPayment(page, { currency: 'EUR' });
  await payWithRedirectMethod(page, METHOD, 'authorize');
  const orderCode = await expectOrderConfirmation(page);

  const { order, intent } = await expectOneAuthorizedOrder(orderCode);
  expect(intent.status).toBe('succeeded');
  expect(intent.payment_method_types).toContain(METHOD);
  expect(netCaptured(intent)).toBe(minor(order.totalPrice));
  expect((await getPaymentIntentRecord(intent.id))?.state).toBe('CAPTURED');
});
