import { expect, FrameLocator, Locator, Page } from '@playwright/test';
import {
  addDefaultAddress,
  createCartWithProduct,
  Currency,
  Customer,
  findInStockProduct,
  OccCart,
  Product,
  registerCustomer,
} from './occ';

export const CARDS = {
  noThreeDs: '4242 4242 4242 4242',
  threeDs: '4000 0025 0000 3155',
  declined: '4000 0000 0000 0002',
} as const;

/**
 * Known bug in the Spartacus library (found in Task 16): the component state is correct, but the view is not
 * refreshed. Stripe's own message inside the Payment Element is shown.
 */
export const UI_REFRESH_BUG = {
  type: 'known bug B1',
  description:
    'StripePaymentFormComponent changes its state without markForCheck inside the OnPush Spartacus page slot: after a ' +
    'failed confirmation or a failed redirect return the button stays "Processing..." and no shop error is shown',
};

/** Known bug in the connector: a failed or cancelled PaymentIntent gives SAP's generic error, not a Stripe reason. */
export const ERROR_CODE_BUG = {
  type: 'known bug B2',
  description:
    'POST /orders with a failed PaymentIntent answers PaymentAuthorizationError instead of StripePaymentError ' +
    'STRIPE_PAYMENT_FAILED (the authorizer returns false and SAP throws PaymentAuthorizationException)',
};

/** Starts to wait for the storefront's POST /orders and gives its status and body. Call it before the action. */
export function waitForPlaceOrder(page: Page): Promise<{ status: number; body: any }> {
  return page
    .waitForResponse((r) => r.request().method() === 'POST' && /\/users\/[^/]+\/orders\?/.test(r.url()), {
      timeout: 120_000,
    })
    .then(async (r) => ({ status: r.status(), body: await r.json().catch(() => undefined) }));
}

/** After a failed confirmation the shop shows its error and the customer can pay again. */
export async function expectPaymentStepReady(page: Page): Promise<void> {
  await expect(paymentError(page)).toBeVisible({ timeout: 10_000 });
  await expect(payButton(page)).toHaveText(/pay now with stripe/i, { timeout: 10_000 });
  await expect(payButton(page)).toBeEnabled();
}

export const MESSAGES = {
  amountChanged: /the order total changed/i,
  paymentFailed: /the payment failed/i,
  declined: /your card (was|has been) declined/i,
} as const;

export function sitePath(currency: Currency): string {
  return `/electronics-spa/en/${currency}`;
}

export interface CheckoutSession {
  customer: Customer;
  cart: OccCart;
  product: Product;
  currency: Currency;
}

/** The cookie banner can appear at any time: close it whenever it blocks an action. */
export async function closeCookieBanner(page: Page): Promise<void> {
  await page.addLocatorHandler(page.getByRole('button', { name: /allow all/i }), async (button) => {
    await button.click();
  });
}

export async function login(page: Page, customer: Customer, currency: Currency): Promise<void> {
  await page.goto(`${sitePath(currency)}/login`);
  await page.locator('input[formcontrolname="userId"]').fill(customer.email);
  await page.locator('input[formcontrolname="password"]').fill(customer.password);
  await page.getByRole('button', { name: /sign in/i }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

/**
 * Registers a new customer with an address, puts an in-stock product in a new cart (by OCC), logs in and goes through
 * the checkout to the payment step. Waits for the Stripe Payment Element.
 */
export async function checkoutToPayment(
  page: Page,
  options: { currency: Currency; quantity?: number }
): Promise<CheckoutSession> {
  const { currency } = options;
  const customer = await registerCustomer();
  await addDefaultAddress(customer);
  const product = await findInStockProduct(currency);
  const cart = await createCartWithProduct(customer, currency, product.code, options.quantity ?? 1);

  await closeCookieBanner(page);
  await login(page, customer, currency);
  await page.goto(`${sitePath(currency)}/cart`);
  await page.locator('cx-progress-button').filter({ hasText: /proceed to checkout/i }).click();

  await expect(page).toHaveURL(/checkout\/delivery-address/);
  await page.getByRole('button', { name: /continue/i }).click();
  await expect(page).toHaveURL(/checkout\/delivery-mode/);
  await page.getByRole('button', { name: /continue/i }).click();
  await expect(page).toHaveURL(/checkout\/payment-details/);
  await expect(paymentElement(page).locator('input').first()).toBeAttached({ timeout: 30_000 });
  return { customer, cart, product, currency };
}

export function paymentElement(page: Page): FrameLocator {
  return page.frameLocator('.payment-element iframe').first();
}

export function payButton(page: Page): Locator {
  return page.locator('cx-stripe-payment-form button[type="submit"]');
}

export function paymentError(page: Page): Locator {
  return page.locator('cx-stripe-payment-form .error');
}

/** Fills the card in the Payment Element and pays. 3DS cards need completeThreeDs afterwards. */
export async function payWithCard(page: Page, number: string): Promise<void> {
  const element = paymentElement(page);
  await element.getByRole('tab', { name: /card/i }).or(element.getByText('Card', { exact: true })).first().click();
  await element.locator('[name="number"]').fill(number);
  await element.locator('[name="expiry"]').fill('12 / 34');
  await element.locator('[name="cvc"]').fill('123');
  const postalCode = element.locator('[name="postalCode"]');
  if (await postalCode.isVisible().catch(() => false)) {
    await postalCode.fill('10115');
  }
  await payButton(page).click();
}

/** Answers the Stripe 3DS test challenge with "Complete" or "Fail". The challenge sits in nested Stripe frames. */
export async function completeThreeDs(page: Page, outcome: 'complete' | 'fail'): Promise<void> {
  const name = outcome === 'complete' ? /^\s*complete\s*$/i : /^\s*fail\s*$/i;
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const challenge = page.frame({ name: 'stripe-challenge-frame' });
    const button = challenge?.getByRole('button', { name });
    if (button && (await button.isVisible().catch(() => false))) {
      // The modal animates in; a click during the animation can be lost, so click until the challenge closes.
      await button.click({ timeout: 5_000 }).catch(() => undefined);
      await page.waitForTimeout(2_000);
      if (!(await button.isVisible().catch(() => false))) {
        return;
      }
    } else {
      await page.waitForTimeout(500);
    }
  }
  throw new Error('The 3DS challenge did not appear or did not close');
}

/**
 * Pays with Bancontact. On the Stripe test page the customer authorizes or fails the payment. With
 * returnToShop=false the browser never comes back: the return to the shop is blocked and the page is closed.
 * onProviderPage runs while the customer is on the provider page (for example, to change the cart in another tab).
 */
export async function payWithBancontact(
  page: Page,
  outcome: 'authorize' | 'fail',
  returnToShop: boolean,
  onProviderPage?: () => Promise<void>
): Promise<void> {
  const element = paymentElement(page);
  await element.getByRole('tab', { name: /bancontact/i }).or(element.getByText('Bancontact')).first().click();
  await element.locator('input[name="name"]').fill('E2E Stripe');
  await payButton(page).click();
  await page.waitForURL(/stripe\.com/, { timeout: 60_000 });
  if (onProviderPage) {
    await onProviderPage();
  }
  if (!returnToShop) {
    await page.route(/localhost:4200/, (route) => route.abort());
  }
  const action = outcome === 'authorize' ? /authorize test payment/i : /fail test payment/i;
  const leaving = page.waitForRequest(/localhost:4200/, { timeout: 30_000 });
  await page.getByRole('link', { name: action }).or(page.getByRole('button', { name: action })).first().click();
  if (!returnToShop) {
    await leaving;
    await page.close();
  } else {
    leaving.catch(() => undefined);
  }
}

/** Waits for the order confirmation page and gives the order code. */
export async function expectOrderConfirmation(page: Page): Promise<string> {
  await expect(page).toHaveURL(/order-confirmation/, { timeout: 90_000 });
  const title = page.locator('.cx-page-title');
  await expect(title).toHaveText(/confirmation of order:\s*\w+/i);
  await expect(page.getByRole('heading', { name: /thank you for your order/i })).toBeVisible();
  const code = (await title.innerText()).match(/order:\s*(\w+)/i)?.[1];
  if (!code) {
    throw new Error('No order code on the confirmation page');
  }
  return code;
}

/** Goes through the checkout, pays with a card without 3DS and gives the session and the order code. */
export async function placeOrderWithCard(
  page: Page,
  options: { currency: Currency; quantity?: number }
): Promise<CheckoutSession & { orderCode: string }> {
  const session = await checkoutToPayment(page, options);
  await payWithCard(page, CARDS.noThreeDs);
  return { ...session, orderCode: await expectOrderConfirmation(page) };
}

/** Goes through the checkout, pays with Bancontact (charged at once) and gives the session and the order code. */
export async function placeOrderWithBancontact(page: Page): Promise<CheckoutSession & { orderCode: string }> {
  const session = await checkoutToPayment(page, { currency: 'EUR' });
  await payWithBancontact(page, 'authorize', true);
  return { ...session, orderCode: await expectOrderConfirmation(page) };
}
