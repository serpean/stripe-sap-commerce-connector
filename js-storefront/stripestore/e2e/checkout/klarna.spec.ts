import { expect, test } from '@playwright/test';
import { expectOneAuthorizedOrder } from '../support/assertions';
import { getPaymentIntentRecord, shipOrder, waitForOrderStatus, waitForPaymentEntry } from '../support/backend';
import { checkoutToPayment, expectOrderConfirmation, payWithKlarna } from '../support/checkout';
import { minor, netCaptured, retrievePaymentIntent } from '../support/stripe';

test('12. Klarna: the money is reserved, shipping in OMS captures the order total', async ({ page }) => {
  await checkoutToPayment(page, { currency: 'EUR' });
  await payWithKlarna(page, 'approve');
  const orderCode = await expectOrderConfirmation(page);

  // Klarna reserves the money like a card in manual mode.
  const { order, intent } = await expectOneAuthorizedOrder(orderCode);
  expect(intent.status).toBe('requires_capture');
  expect(intent.amount_capturable).toBe(minor(order.totalPrice));
  expect((await getPaymentIntentRecord(intent.id))?.state).toBe('AUTHORIZED');

  await waitForOrderStatus(orderCode, 'READY');
  await shipOrder(orderCode);
  const entries = await waitForPaymentEntry(orderCode, 'CAPTURE');
  expect(entries.map((e) => [e.type, e.status])).toEqual([
    ['AUTHORIZATION', 'ACCEPTED'],
    ['CAPTURE', 'ACCEPTED'],
  ]);
  await waitForOrderStatus(orderCode, 'COMPLETED');

  const captured = await retrievePaymentIntent(intent.id);
  expect(captured.status).toBe('succeeded');
  expect(netCaptured(captured)).toBe(minor(order.totalPrice));
});
