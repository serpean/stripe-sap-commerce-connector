import { expect, test } from '@playwright/test';
import { expectNoOrderAndCartIntact } from '../support/assertions';
import {
  CARDS,
  checkoutToPayment,
  expectPaymentStepReady,
  MESSAGES,
  paymentElement,
  payWithCard,
  UI_REFRESH_BUG,
} from '../support/checkout';

test('4. declined card: no order', async ({ page }) => {
  const session = await checkoutToPayment(page, { currency: 'USD' });
  await payWithCard(page, CARDS.declined);

  await expect(paymentElement(page).getByText(MESSAGES.declined).first()).toBeVisible();
  await expect(page).toHaveURL(/checkout\/payment-details/);
  await expectNoOrderAndCartIntact(session);
});

test('4 (UI). after a declined card the shop shows its error and the customer can pay again', async ({ page }) => {
  test.info().annotations.push(UI_REFRESH_BUG);
  test.fail();
  await checkoutToPayment(page, { currency: 'USD' });
  await payWithCard(page, CARDS.declined);
  await expectPaymentStepReady(page);
});
