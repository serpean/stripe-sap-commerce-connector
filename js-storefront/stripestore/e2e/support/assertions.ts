import { expect } from '@playwright/test';
import {
  getOrderCodesForPaymentIntent,
  getOrderCodesOfCustomer,
  getPaymentEntries,
  getPaymentIntentRecordsOfCart,
  getSapOrder,
  PaymentEntry,
  SapOrder,
} from './backend';
import { CheckoutSession } from './checkout';
import { getCart } from './occ';
import { minor, netCaptured, PaymentIntent, retrievePaymentIntent } from './stripe';

/** No order for the customer, the cart is still there with the same product, and no money is held in Stripe. */
export async function expectNoOrderAndCartIntact(session: CheckoutSession): Promise<void> {
  expect(await getOrderCodesOfCustomer(session.customer)).toEqual([]);
  const cart = await getCart(session.customer, session.cart.code, session.currency);
  expect(cart?.entries?.map((e) => e.product.code)).toEqual([session.product.code]);
  for (const record of await getPaymentIntentRecordsOfCart(session.cart.code)) {
    expect(record.orderCode).toBeNull();
    const intent = await retrievePaymentIntent(record.paymentIntentId);
    expect(['requires_payment_method', 'requires_confirmation', 'requires_action', 'canceled']).toContain(intent.status);
    expect(netCaptured(intent)).toBe(0);
  }
}

export interface PlacedOrder {
  order: SapOrder;
  entries: PaymentEntry[];
  intent: PaymentIntent;
}

/**
 * Exactly one order for the PaymentIntent, an accepted AUTHORIZATION for the order total, and a PaymentIntent of the
 * same amount and currency.
 */
export async function expectOneAuthorizedOrder(orderCode: string): Promise<PlacedOrder> {
  const order = (await getSapOrder(orderCode))!;
  expect(order, `order ${orderCode}`).toBeDefined();
  const paymentIntentId = order.paymentIntentId!;
  expect(await getOrderCodesForPaymentIntent(paymentIntentId)).toEqual([orderCode]);
  const entries = await getPaymentEntries(orderCode);
  const authorizations = entries.filter((e) => e.type === 'AUTHORIZATION');
  expect(authorizations.map((e) => [e.status, e.requestId])).toEqual([['ACCEPTED', paymentIntentId]]);
  expect(authorizations[0].amount).toBeCloseTo(order.totalPrice, 2);
  const intent = await retrievePaymentIntent(paymentIntentId);
  expect(intent.amount).toBe(minor(order.totalPrice));
  expect(intent.currency).toBe(order.currency.toLowerCase());
  return { order, entries, intent };
}
