import { expect, FrameLocator, Locator, Page, Route } from '@playwright/test';
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
import { refillTestStock } from './backend';

/** Products whose stock this worker already refilled. */
const refilled = new Set<string>();

/**
 * Refills the stock of the product the test picked, once per worker. The search index can make a test pick a product
 * that the global setup did not see (the index cron job changes the stock status during the run).
 */
async function ensureTestStock(productCode: string): Promise<void> {
  if (refilled.has(productCode)) {
    return;
  }
  const changes = await refillTestStock([productCode]);
  changes.forEach((line) => console.log(`Test stock refilled: ${line}`));
  refilled.add(productCode);
}

export const CARDS = {
  noThreeDs: '4242 4242 4242 4242',
  threeDs: '4000 0025 0000 3155',
  declined: '4000 0000 0000 0002',
} as const;

const PLACE_ORDER_URL = /\/users\/[^/]+\/orders\?/;

/**
 * Starts to wait for the storefront's POST /orders and gives its status and body. Call it before the action. The
 * request goes through a route, so the body is read before the browser can drop it (a navigation right after the
 * response makes the body unavailable to waitForResponse).
 */
export async function waitForPlaceOrder(page: Page): Promise<{ status: number; body: any }> {
  let resolve!: (value: { status: number; body: any }) => void;
  const result = new Promise<{ status: number; body: any }>((r) => (resolve = r));
  const handler = async (route: Route) => {
    if (route.request().method() !== 'POST') {
      await route.continue();
      return;
    }
    const response = await route.fetch();
    const text = await response.text();
    let body: any;
    try {
      body = JSON.parse(text);
    } catch {
      body = undefined;
    }
    await route.fulfill({ response, body: text });
    await page.unroute(PLACE_ORDER_URL, handler);
    resolve({ status: response.status(), body });
  };
  await page.route(PLACE_ORDER_URL, handler);
  const timeout = new Promise<never>((_, reject) =>
    setTimeout(() => reject(new Error('No POST /orders within 120 s')), 120_000).unref?.()
  );
  return Promise.race([result, timeout]);
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
  await ensureTestStock(product.code);
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
  // The Stripe test challenge can take more than a minute to load on a busy machine.
  const deadline = Date.now() + 120_000;
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

/** Matches the storefront origin (the return URL of the provider pages); E2E_BASE_URL can differ from :4200. */
function shopOriginPattern(): RegExp {
  const host = new URL(process.env['E2E_BASE_URL'] ?? 'http://localhost:4200').host;
  return new RegExp(host.replace(/\./g, '\\.'));
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
  // With more methods enabled, Bancontact sits in the overflow list of the Payment Element, not in a tab.
  await choosePaymentMethod(page, 'bancontact', /bancontact/i);
  const element = paymentElement(page);
  await element.locator('input[name="name"]').fill('E2E Stripe');
  await payButton(page).click();
  await page.waitForURL(/stripe\.com/, { timeout: 60_000 });
  if (onProviderPage) {
    await onProviderPage();
  }
  const shopUrl = shopOriginPattern();
  if (!returnToShop) {
    await page.route(shopUrl, (route) => route.abort());
  }
  const action = outcome === 'authorize' ? /authorize test payment/i : /fail test payment/i;
  const leaving = page.waitForRequest(shopUrl, { timeout: 30_000 });
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

/** Picks the payment method in the Payment Element: its tab, or the overflow list (a select) when there is no room. */
export async function choosePaymentMethod(page: Page, value: string, tabName: RegExp): Promise<void> {
  const element = paymentElement(page);
  const tab = element.getByRole('tab', { name: tabName });
  if (await tab.count()) {
    await tab.first().click();
  } else {
    await element.locator(`select:has(option[value="${value}"])`).first().selectOption(value);
  }
}

/** Clicks the Stripe test page button of the outcome, and waits for the browser to leave to the shop. */
async function answerTestPage(page: Page, authorize: boolean): Promise<void> {
  const action = authorize ? /authorize test payment/i : /fail test payment/i;
  await page.getByRole('link', { name: action }).or(page.getByRole('button', { name: action })).first().click();
}

/**
 * Pays with Klarna. Klarna reserves the money (requires_capture). In test mode the browser goes to the Klarna
 * playground: a phone number, any 6-digit code (not 999999) and the confirmation of the payment plan. The page can be
 * in Spanish. With 'decline' the customer closes the Klarna window, which brings the browser back to the shop.
 */
export async function payWithKlarna(page: Page, outcome: 'approve' | 'decline'): Promise<void> {
  await choosePaymentMethod(page, 'klarna', /klarna/i);
  await payButton(page).click();
  await page.waitForURL(/klarna\.com/, { timeout: 60_000 });
  const shopUrl = shopOriginPattern();
  const phone = page.locator('input[name="phone"]');
  await phone.fill('600000000');
  await page.getByRole('button', { name: /continue|continuar/i }).click();
  await page.locator('input[name="otp_field"]').pressSequentially('123456');
  const confirmation = page.getByRole('button', { name: /^(continue|continuar|pay|pagar)/i });
  await expect(confirmation.first()).toBeVisible({ timeout: 60_000 });
  if (outcome === 'decline') {
    // The close button (an X without a name) is the last button of the page; Klarna may ask to confirm.
    await page.getByRole('button').last().click();
    await page.getByRole('button', { name: /cancel|cancelar|yes|sí|sure|seguro/i }).first().click({ timeout: 5_000 }).catch(() => undefined);
    return;
  }
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline && !shopUrl.test(page.url())) {
    // The page has two steps with the same button (plan, then confirmation); the offers load a moment after the page.
    await addKlarnaTestCardIfAsked(page);
    await confirmation.first().click({ timeout: 3_000 }).catch(() => undefined);
    await page.waitForTimeout(2_000);
  }
}

/**
 * The Klarna playground can ask the customer to add a card ("Add a payment method"). The sample card of
 * docs.klarna.com (sample payment data): 4111 1111 1111 1111, any future expiry date, CVC 123.
 */
async function addKlarnaTestCardIfAsked(page: Page): Promise<void> {
  if (!(await fillInAnyFrame(page, /número de tarjeta|card number/i, '4111111111111111'))) {
    return;
  }
  // Each card field can sit in its own frame.
  await fillInAnyFrame(page, /fecha de caducidad|expir|mm\s*\/\s*aa|mm\s*\/\s*yy/i, '12/30');
  await fillInAnyFrame(page, /cvc|cvv|security code/i, '123');
  await page.getByRole('button', { name: /agrega tarjeta|add card/i }).first().click({ timeout: 5_000 }).catch(() => undefined);
  await page.waitForTimeout(2_000);
}

/** Fills the first visible field with the label (or placeholder) in any frame of the page; false when there is none. */
async function fillInAnyFrame(page: Page, label: RegExp, value: string): Promise<boolean> {
  for (const frame of page.frames()) {
    for (const field of [frame.getByLabel(label).first(), frame.getByPlaceholder(label).first()]) {
      if (await field.isVisible().catch(() => false)) {
        await field.fill(value, { timeout: 5_000 });
        return true;
      }
    }
  }
  return false;
}

/** Pays with a redirect method (for example bizum or eps) that has the Stripe test page, and answers it. */
export async function payWithRedirectMethod(page: Page, method: string, outcome: 'authorize' | 'fail'): Promise<void> {
  await choosePaymentMethod(page, method, new RegExp(method, 'i'));
  const element = paymentElement(page);
  if (method === 'eps') {
    await element.locator('select[name="bank"]').selectOption({ index: 1 });
  }
  const name = element.locator('input[name="name"]');
  if (await name.isVisible().catch(() => false)) {
    await name.fill('E2E Stripe');
  }
  const phone = element.locator('input[name="phoneNumber"]');
  if (await phone.isVisible().catch(() => false)) {
    await phone.fill('600000000');
  }
  await payButton(page).click();
  // Some test methods (bizum) finish without a provider page: the browser goes straight back to the shop.
  const onProviderPage = await page.waitForURL(/stripe\.com/, { timeout: 30_000 }).then(() => true, () => false);
  if (onProviderPage) {
    await answerTestPage(page, outcome === 'authorize');
  }
}

/** Test IBANs of docs.stripe.com/testing#sepa-direct-debit. */
export const SEPA_IBANS = {
  succeedsAfterDelay: 'DE89370400440532013000',
  fails: 'DE62370400440532013001',
} as const;

/** Pays with SEPA Direct Debit. The payment stays in processing: the customer comes back to the confirmation page. */
export async function payWithSepa(page: Page, iban: string): Promise<void> {
  await choosePaymentMethod(page, 'sepa_debit', /sepa/i);
  const element = paymentElement(page);
  // The country first: changing it redraws the address fields and can clear what was typed before.
  await element.locator('select[name="country"][aria-label="Country or region"]').selectOption('DE');
  await element.locator('input[name="iban"]').fill(iban);
  await element.locator('input[name="email"]').fill('e2e-sepa@example.com');
  await element.locator('input[name="name"]').fill('E2E Stripe');
  await element.locator('input[name="addressLine1"]').fill('Hauptstrasse 1');
  await element.locator('input[name="postalCode"]').fill('10115');
  await element.locator('input[name="locality"]').fill('Berlin');
  await expect(element.locator('input[name="iban"]')).toHaveValue(/DE\d\d/);
  await expect(element.locator('input[name="email"]')).toHaveValue('e2e-sepa@example.com');
  // The Payment Element reports the form as complete a moment after the last field; a click before that does nothing
  // (no request, no message). Click again until Stripe gets the confirmation.
  for (let attempt = 0; attempt < 4; attempt++) {
    const confirmation = page.waitForRequest(/api\.stripe\.com\/v1\/payment_intents\/.*\/confirm/, { timeout: 8_000 });
    await payButton(page).click();
    if (await confirmation.then(() => true, () => false)) {
      return;
    }
  }
  throw new Error('The SEPA form was never confirmed at Stripe');
}
/**
 * Pays with Multibanco. The Payment Element asks for the email, and Stripe picks the test scenario from it (see
 * docs.stripe.com/payments/multibanco/accept-a-payment, "Test your integration"). The voucher does not open a page:
 * the customer comes back to the confirmation page.
 */
export async function payWithMultibanco(page: Page, email: string): Promise<void> {
  await choosePaymentMethod(page, 'multibanco', /multibanco/i);
  const element = paymentElement(page);
  const field = element.locator('input[name="email"]');
  if (await field.isVisible().catch(() => false)) {
    await field.fill(email);
  }
  await confirmWhenComplete(page);
  await closeInstructionsModal(page);
}

/**
 * Stripe.js shows the Multibanco voucher or the bank transfer details in a modal (a Stripe frame) and finishes the
 * confirmation when the customer closes it. The browser then goes on to the order confirmation page.
 */
async function closeInstructionsModal(page: Page): Promise<void> {
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline && !/order-confirmation/.test(page.url())) {
    for (const frame of page.frames()) {
      const close = frame.getByRole('button', { name: /close/i }).first();
      if (await frame.getByText(/test Multibanco voucher|test mode bank transfer/i).first().isVisible().catch(() => false)) {
        if (await close.isVisible().catch(() => false)) {
          await close.click({ timeout: 5_000 }).catch(() => undefined);
          return;
        }
      }
    }
    await page.waitForTimeout(500);
  }
}

/** Pays with a bank transfer (customer_balance, EU bank transfer). The customer comes back to the confirmation page. */
export async function payWithBankTransfer(page: Page): Promise<void> {
  await choosePaymentMethod(page, 'customer_balance', /bank transfer/i);
  const field = paymentElement(page).locator('input[name="email"]');
  if (await field.isVisible().catch(() => false)) {
    await field.fill('e2e-bank-transfer@example.com');
  }
  await confirmWhenComplete(page);
  await closeInstructionsModal(page);
}

/**
 * Clicks "Pay now" until Stripe gets the confirmation: the Payment Element reports the form as complete a moment
 * after the last field, and an earlier click does nothing.
 */
async function confirmWhenComplete(page: Page): Promise<void> {
  for (let attempt = 0; attempt < 4; attempt++) {
    const confirmation = page.waitForRequest(/api\.stripe\.com\/v1\/payment_intents\/.*\/confirm/, { timeout: 8_000 });
    await payButton(page).click();
    if (await confirmation.then(() => true, () => false)) {
      return;
    }
  }
  throw new Error('The payment form was never confirmed at Stripe');
}

/** Opens the order details page of the customer's order (the order overview outlet of the library lives there). */
export async function openOrderDetails(page: Page, orderCode: string, currency: Currency): Promise<void> {
  await page.goto(`${sitePath(currency)}/my-account/order/${orderCode}`);
  await expect(page.locator('cx-order-overview')).toBeVisible({ timeout: 60_000 });
}

/**
 * Checks the payment instructions of a pending payment on the current page (component cx-stripe-payment-instructions)
 * and gives the payment reference.
 */
export async function expectPaymentInstructions(page: Page): Promise<{ reference: string }> {
  const instructions = page.locator('cx-stripe-payment-instructions');
  await expect(instructions.getByRole('heading', { name: /complete your payment/i })).toBeVisible({ timeout: 15_000 });
  await expect(instructions.getByRole('link')).toHaveAttribute('href', /^https:\/\//);
  const reference = (await instructions.locator('dd').first().innerText()).trim();
  expect(reference).not.toBe('');
  return { reference };
}
