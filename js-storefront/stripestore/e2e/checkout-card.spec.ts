import { expect, test } from '@playwright/test';
import { addDefaultAddress, registerCustomer } from './support/occ';

const SITE_PATH = '/electronics-spa/en/USD';
// Spartacus product route needs the code and the name. The default product is in stock.
const PRODUCT_PATH = process.env['E2E_PRODUCT_PATH'] ?? 'product/816324/DSC-T20-Cyber-shot-Silver';

test('checkout with a card without 3DS ends on the order confirmation page', async ({ page }) => {
  const customer = await registerCustomer();
  await addDefaultAddress(customer);

  // Login
  await page.goto(`${SITE_PATH}/login`);
  const allowAll = page.getByRole('button', { name: /allow all/i });
  if (await allowAll.isVisible().catch(() => false)) {
    await allowAll.click();
  }
  await page.locator('input[formcontrolname="userId"]').fill(customer.email);
  await page.locator('input[formcontrolname="password"]').fill(customer.password);
  await page.getByRole('button', { name: /sign in/i }).click();
  await expect(page).not.toHaveURL(/\/login/);

  // Add a product to the cart
  await page.goto(`${SITE_PATH}/${PRODUCT_PATH}`);
  await page.getByRole('button', { name: /add to cart/i }).click();
  await page.getByText(/proceed to checkout/i).click();

  // Delivery address: the default address is preselected
  await expect(page).toHaveURL(/checkout\/delivery-address/);
  await page.getByRole('button', { name: /continue/i }).click();

  // Delivery mode: the first mode is preselected
  await expect(page).toHaveURL(/checkout\/delivery-mode/);
  await page.getByRole('button', { name: /continue/i }).click();

  // Payment with the Stripe Payment Element
  await expect(page).toHaveURL(/checkout\/payment-details/);
  const stripe = page.frameLocator('.payment-element iframe').first();
  await stripe.locator('[name="number"]').fill('4242 4242 4242 4242');
  await stripe.locator('[name="expiry"]').fill('12 / 34');
  await stripe.locator('[name="cvc"]').fill('123');
  const postalCode = stripe.locator('[name="postalCode"]');
  if (await postalCode.isVisible().catch(() => false)) {
    await postalCode.fill('10115');
  }
  await page.getByRole('button', { name: /pay now with stripe/i }).click();

  // Order confirmation
  await expect(page).toHaveURL(/order-confirmation/, { timeout: 60_000 });
  await expect(page.locator('.cx-page-title')).toHaveText(/confirmation of order:\s*\w+/i);
  await expect(page.getByRole('heading', { name: /thank you for your order/i })).toBeVisible();
});
