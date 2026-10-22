import { expect, test } from '@playwright/test';
import { getAvailableStock, getOrderCodesOfCustomer, getSapOrder, waitFor, waitForOrderStatus } from '../support/backend';
import { checkoutToPayment, expectOrderConfirmation, payWithSepa, SEPA_IBANS } from '../support/checkout';
import { netCaptured, retrievePaymentIntent } from '../support/stripe';

test('15. SEPA Direct Debit fails: the order is cancelled and the stock is as before the order', async ({ page }) => {
  const session = await checkoutToPayment(page, { currency: 'EUR' });
  const stockBefore = await getAvailableStock(session.product.code);
  await payWithSepa(page, SEPA_IBANS.fails);
  const orderCode = await expectOrderConfirmation(page);

  // The test debit can fail within seconds, so the order may already be cancelled: its PaymentIntent is read from SAP.
  const placed = (await waitFor(async () => (await getSapOrder(orderCode))?.paymentIntentId, 30_000, 'the PaymentIntent of the order'))!;

  // The debit fails at Stripe: the order is cancelled; the money never arrived.
  await waitFor(async () => (await retrievePaymentIntent(placed)).status !== 'processing', 480_000, 'the failed SEPA debit', 10_000);
  const intent = await retrievePaymentIntent(placed);
  expect(intent.status).toBe('requires_payment_method');
  expect(netCaptured(intent)).toBe(0);
  await waitForOrderStatus(orderCode, 'CANCELLED', 240_000);

  // Stock is not reserved before the payment, and a cancelled pending order must not give back what it never took.
  expect(await getAvailableStock(session.product.code)).toBe(stockBefore);
  expect(await getOrderCodesOfCustomer(session.customer)).toEqual([orderCode]);
});
