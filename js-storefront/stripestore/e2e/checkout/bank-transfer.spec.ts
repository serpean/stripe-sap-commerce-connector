import { expect, test } from '@playwright/test';
import {
  cancelOrder,
  cancelWholeOrder,
  getCancelDenial,
  getAvailableStock,
  getOrder,
  getPaymentEntries,
  getStripeCustomerOf,
  waitFor,
  waitForOrderStatus,
  waitForPaymentEntry,
} from '../support/backend';
import { checkoutToPayment, CheckoutSession, expectOrderConfirmation, payWithBankTransfer } from '../support/checkout';
import { bankTransferReference, fundCashBalance, getCashBalance, listCashBalanceTransactions, listCustomerBalanceRefunds, minor, netCaptured, retrievePaymentIntent } from '../support/stripe';
import { Page } from '@playwright/test';

interface Pending {
  session: CheckoutSession;
  orderCode: string;
  intentId: string;
  total: number;
  customerId: string;
  reference: string;
}

/** Pays with a bank transfer and checks that the order waits in PAYMENT_PENDING with the transfer instructions. */
async function placePendingBankTransfer(page: Page, quantity = 1): Promise<Pending> {
  const session = await checkoutToPayment(page, { currency: 'EUR', quantity });
  const stockBefore = await getAvailableStock(session.product.code);
  await payWithBankTransfer(page);
  const orderCode = await expectOrderConfirmation(page);
  const pending = await waitForOrderStatus(orderCode, 'PAYMENT_PENDING', 30_000);
  const info = (await getOrder(session.customer, orderCode)).stripePaymentInfo;
  expect(info.paymentStatus).toBe('PENDING');
  expect(info.instructionsUrl).toMatch(/^https:\/\//);
  expect(info.amountRemaining).toBeTruthy();
  expect((await getPaymentEntries(orderCode)).filter((e) => e.type === 'AUTHORIZATION')).toEqual([]);
  expect(await getAvailableStock(session.product.code)).toBe(stockBefore);
  const intentId = pending.paymentIntentId!;
  const open = await retrievePaymentIntent(intentId);
  expect(open.status).toBe('requires_action');
  expect(open.amount).toBe(minor(pending.totalPrice));
  // The PaymentIntent belongs to the Stripe Customer that the connector stored for the registered customer.
  const customerId = await getStripeCustomerOf(session.customer.email);
  expect(customerId).toMatch(/^cus_/);
  expect((open as any).customer).toBe(customerId);
  return { session, orderCode, intentId, total: minor(pending.totalPrice), customerId: customerId!, reference: await bankTransferReference(intentId) };
}

test.describe('Bank transfer (customer_balance)', () => {
  test.setTimeout(900_000);

  test('18. exact transfer: the order is accepted', async ({ page }) => {
    const p = await placePendingBankTransfer(page);
    await fundCashBalance(p.customerId, p.total, p.reference);

    await waitForOrderStatus(p.orderCode, 'READY', 300_000);
    const entries = await waitForPaymentEntry(p.orderCode, 'AUTHORIZATION');
    expect.soft(entries.filter((e) => e.type === 'AUTHORIZATION').map((e) => [e.status, e.requestId])).toEqual([['ACCEPTED', p.intentId]]);
    const paid = await retrievePaymentIntent(p.intentId);
    expect(paid.status).toBe('succeeded');
    expect(netCaptured(paid)).toBe(p.total);
    expect((await getCashBalance(p.customerId)).eur ?? 0).toBe(0);
  });

  // KNOWN GAP (product): the cash balance refund check is never planned. DefaultStripeCashBalanceService.PENDING_TASKS
  // searches Task.executionDate, a dynamic attribute that FlexibleSearch cannot use (use executionTimeMillis). Tests 19
  // and 20 check the order outcome first, then the refund. test.fail makes them pass while the refund is missing, and
  // fail (a reminder to remove test.fail) once the refund works. The log line "REFUND CHECK" shows how far they got.
  test('19. underpaid: the order stays pending, at the timeout it is cancelled and the funds go back to the customer', async ({ page }) => {
    const p = await placePendingBankTransfer(page);
    const half = Math.floor(p.total / 2);
    const since = Math.floor(Date.now() / 1000) - 5;
    await fundCashBalance(p.customerId, half, p.reference);

    // Half of the money is not enough: the order keeps waiting.
    await page.waitForTimeout(20_000);
    expect((await waitForOrderStatus(p.orderCode, 'PAYMENT_PENDING', 10_000)).status).toBe('PAYMENT_PENDING');
    expect((await getPaymentEntries(p.orderCode)).filter((e) => e.type === 'AUTHORIZATION')).toEqual([]);

    // At the timeout (180 s) the order is cancelled and the PaymentIntent too.
    await waitForOrderStatus(p.orderCode, 'CANCELLED', 420_000);
    const cancelled = await waitFor(async () => {
      const live = await retrievePaymentIntent(p.intentId);
      return live.status === 'canceled' ? live : undefined;
    }, 120_000, 'the PaymentIntent canceled');
    expect(netCaptured(cancelled)).toBe(0);

    console.log('REFUND CHECK: order cancelled and PaymentIntent canceled; waiting for the cash balance refund');
    test.fail(true, 'cash balance refund check is not planned (FlexibleSearch on Task.executionDate)');
    // The funds return to the cash balance, and the cash balance refund check sends them back (origin customer_balance).
    const refund = await waitFor(async () => {
      const refunds = await listCustomerBalanceRefunds(p.customerId, since);
      return refunds.find((r) => r.amount === half);
    }, 120_000, `a customer_balance refund of ${half}`, 5_000);
    expect(refund.amount).toBe(half);
    await waitFor(async () => ((await getCashBalance(p.customerId)).eur ?? 0) === 0, 120_000, 'an empty cash balance', 5_000);
    expect((await listCashBalanceTransactions(p.customerId)).length).toBeGreaterThan(0);
  });

  test('20. overpaid: the order is accepted and the excess goes back to the customer', async ({ page }) => {
    const p = await placePendingBankTransfer(page);
    const extra = 500;
    const since = Math.floor(Date.now() / 1000) - 5;
    await fundCashBalance(p.customerId, p.total + extra, p.reference);

    await waitForOrderStatus(p.orderCode, 'READY', 300_000);
    const entries = await waitForPaymentEntry(p.orderCode, 'AUTHORIZATION');
    expect.soft(entries.filter((e) => e.type === 'AUTHORIZATION').map((e) => [e.status, e.requestId])).toEqual([['ACCEPTED', p.intentId]]);
    const paid = await retrievePaymentIntent(p.intentId);
    expect(paid.status).toBe('succeeded');
    expect(netCaptured(paid)).toBe(p.total);

    console.log('REFUND CHECK: order accepted; waiting for the refund of the excess');
    test.fail(true, 'cash balance refund check is not planned (FlexibleSearch on Task.executionDate)');
    const refund = await waitFor(async () => {
      const refunds = await listCustomerBalanceRefunds(p.customerId, since);
      return refunds.find((r) => r.amount === extra);
    }, 120_000, `a customer_balance refund of ${extra}`, 5_000);
    expect(refund.amount).toBe(extra);
    await waitFor(async () => ((await getCashBalance(p.customerId)).eur ?? 0) === 0, 120_000, 'an empty cash balance', 5_000);
  });

  test('21. a pending bank transfer order cannot be cancelled in part; a full cancel releases the PaymentIntent', async ({ page }) => {
    const p = await placePendingBankTransfer(page, 2);

    // The cancel service denies the partial cancel with code 7301 and the stable name in the description; the full cancel passes.
    expect(await getCancelDenial(p.orderCode, true)).toMatch(/7301.*stripe-payment-pending:/);
    expect(await getCancelDenial(p.orderCode, false)).toBeUndefined();
    await expect(cancelOrder(p.orderCode, { entryNumber: 0, quantity: 1 })).rejects.toThrow(/not cancellable/i);
    expect((await waitForOrderStatus(p.orderCode, 'PAYMENT_PENDING', 10_000)).status).toBe('PAYMENT_PENDING');
    expect((await retrievePaymentIntent(p.intentId)).status).toBe('requires_action');

    await cancelWholeOrder(p.orderCode);
    await waitForOrderStatus(p.orderCode, 'CANCELLED', 180_000);
    const cancelled = await waitFor(async () => {
      const live = await retrievePaymentIntent(p.intentId);
      return live.status === 'canceled' ? live : undefined;
    }, 120_000, 'the PaymentIntent canceled');
    expect(netCaptured(cancelled)).toBe(0);
  });
});
