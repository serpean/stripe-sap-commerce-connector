import { APIRequestContext, APIResponse, request } from '@playwright/test';

export const OCC_URL = process.env['E2E_OCC_URL'] ?? 'https://localhost:9002';
export const BASE_SITE = 'electronics-spa';
export const OCC_BASE = `${OCC_URL}/occ/v2/${BASE_SITE}`;
const CLIENT_ID = 'mobile_android';
const CLIENT_SECRET = 'secret';

export type Currency = 'USD' | 'EUR';

export interface Customer {
  email: string;
  password: string;
}

export interface Product {
  code: string;
  name: string;
  price: number;
}

/** Runs the callback with a new request context and always disposes of it. */
export async function withApi<T>(callback: (api: APIRequestContext) => Promise<T>): Promise<T> {
  const api = await request.newContext({ ignoreHTTPSErrors: true });
  try {
    return await callback(api);
  } finally {
    await api.dispose();
  }
}

async function expectOk(response: APIResponse, what: string): Promise<APIResponse> {
  if (!response.ok()) {
    throw new Error(`${what} failed: ${response.status()} ${await response.text()}`);
  }
  return response;
}

async function token(form: Record<string, string>): Promise<string> {
  return withApi(async (api) => {
    const response = await api.post(`${OCC_URL}/authorizationserver/oauth/token`, {
      form: { client_id: CLIENT_ID, client_secret: CLIENT_SECRET, ...form },
    });
    await expectOk(response, `OAuth token (${form['grant_type']})`);
    return (await response.json()).access_token as string;
  });
}

export function clientToken(): Promise<string> {
  return token({ grant_type: 'client_credentials' });
}

export function customerToken(customer: Customer): Promise<string> {
  return token({ grant_type: 'password', username: customer.email, password: customer.password });
}

function userPath(customer: Customer): string {
  return `${OCC_BASE}/users/${encodeURIComponent(customer.email)}`;
}

/** Registers a new customer with a unique email, so that every test run uses a new customer. */
export async function registerCustomer(): Promise<Customer> {
  const unique = `${Date.now()}${Math.floor(Math.random() * 1000)}`;
  const customer = { email: `e2e+${unique}@example.com`, password: 'Stripe-e2e-1234!' };
  const bearer = await clientToken();
  await withApi(async (api) =>
    expectOk(
      await api.post(`${OCC_BASE}/users`, {
        headers: { Authorization: `Bearer ${bearer}` },
        data: { uid: customer.email, password: customer.password, firstName: 'E2E', lastName: 'Stripe', titleCode: 'mr' },
      }),
      'Customer registration'
    )
  );
  return customer;
}

/** Adds a default US delivery address to the customer. */
export async function addDefaultAddress(customer: Customer): Promise<void> {
  const bearer = await customerToken(customer);
  await withApi(async (api) =>
    expectOk(
      await api.post(`${userPath(customer)}/addresses`, {
        headers: { Authorization: `Bearer ${bearer}` },
        data: {
          firstName: 'E2E',
          lastName: 'Stripe',
          titleCode: 'mr',
          line1: '1 Market Street',
          town: 'San Francisco',
          postalCode: '94105',
          phone: '4155550100',
          country: { isocode: 'US' },
          region: { isocode: 'US-CA' },
          defaultAddress: true,
        },
      }),
      'Address creation'
    )
  );
}

const productCache = new Map<Currency, Product>();

/** Stock statuses of the product detail that the shop can sell. */
const SELLABLE = new Set(['inStock', 'lowStock']);

interface Candidate extends Product {
  /** Stock status from the product detail, which asks the stock service (the search status comes from Solr). */
  stockStatus?: string;
}

/**
 * The products that {@link findInStockProduct} can pick for the currency, cheapest first: in stock in the search index,
 * purchasable, with a price of at least 20 in the currency. At most 10. The global setup refills their stock.
 */
export async function findProductCandidates(currency: Currency): Promise<Candidate[]> {
  return withApi(async (api) => {
    const search = await expectOk(
      await api.get(`${OCC_BASE}/products/search`, {
        params: { query: ':relevance', pageSize: '100', fields: 'products(code,name,stock(FULL))', curr: currency },
      }),
      'Product search'
    );
    const inStock = ((await search.json()).products ?? []).filter(
      (p: { stock?: { stockLevelStatus?: string } }) => p.stock?.stockLevelStatus === 'inStock'
    );
    const priced: Candidate[] = [];
    for (const candidate of inStock) {
      const detail = await api.get(`${OCC_BASE}/products/${candidate.code}`, {
        params: { fields: 'code,name,price(value,currencyIso),stock(FULL),purchasable', curr: currency },
      });
      if (!detail.ok()) {
        continue;
      }
      const body = await detail.json();
      if (body.purchasable && body.price?.currencyIso === currency && body.price.value >= 20) {
        priced.push({ code: body.code, name: body.name, price: body.price.value, stockStatus: body.stock?.stockLevelStatus });
      }
      if (priced.length >= 10) {
        break;
      }
    }
    return priced.sort((a, b) => a.price - b.price);
  });
}

/**
 * Finds a product that is in stock and has a price in the currency. The tests do not depend on the stock of one
 * product. The cheapest suitable product above 20 is used, so that the amounts stay small but not trivial. The search
 * index can be older than the stock, so the stock status of the product detail must be sellable too.
 */
export async function findInStockProduct(currency: Currency): Promise<Product> {
  const cached = productCache.get(currency);
  if (cached) {
    return cached;
  }
  const sellable = (await findProductCandidates(currency)).find((p) => SELLABLE.has(p.stockStatus ?? ''));
  if (!sellable) {
    throw new Error(`No product in stock with a ${currency} price`);
  }
  const product: Product = { code: sellable.code, name: sellable.name, price: sellable.price };
  productCache.set(currency, product);
  return product;
}

export interface OccCart {
  code: string;
  guid: string;
  totalPriceWithTax?: { value: number; currencyIso: string };
  totalPrice?: { value: number; currencyIso: string };
  entries?: { entryNumber: number; quantity: number; product: { code: string } }[];
}

/** Creates a new cart for the customer in the currency and adds the product. */
export async function createCartWithProduct(
  customer: Customer,
  currency: Currency,
  productCode: string,
  quantity = 1
): Promise<OccCart> {
  const bearer = await customerToken(customer);
  return withApi(async (api) => {
    const headers = { Authorization: `Bearer ${bearer}` };
    const created = await expectOk(
      await api.post(`${userPath(customer)}/carts`, { headers, params: { curr: currency, fields: 'code,guid' } }),
      'Cart creation'
    );
    const cart = await created.json();
    await expectOk(
      await api.post(`${userPath(customer)}/carts/${cart.code}/entries`, {
        headers,
        params: { curr: currency },
        data: { product: { code: productCode }, quantity },
      }),
      'Add to cart'
    );
    return cart;
  });
}

/** Changes the quantity of a cart entry, so that the cart total changes. */
export async function updateCartEntry(
  customer: Customer,
  cartCode: string,
  entryNumber: number,
  quantity: number,
  currency: Currency
): Promise<void> {
  const bearer = await customerToken(customer);
  await withApi(async (api) =>
    expectOk(
      await api.patch(`${userPath(customer)}/carts/${cartCode}/entries/${entryNumber}`, {
        headers: { Authorization: `Bearer ${bearer}` },
        params: { curr: currency },
        data: { quantity },
      }),
      'Cart entry update'
    )
  );
}

/** Reads a cart of the customer; gives undefined when the cart does not exist anymore (for example, it became an order). */
export async function getCart(customer: Customer, cartCode: string, currency: Currency): Promise<OccCart | undefined> {
  const bearer = await customerToken(customer);
  return withApi(async (api) => {
    const response = await api.get(`${userPath(customer)}/carts/${cartCode}`, {
      headers: { Authorization: `Bearer ${bearer}` },
      params: { curr: currency, fields: 'FULL' },
    });
    if (response.status() === 400 || response.status() === 404) {
      return undefined;
    }
    await expectOk(response, 'Cart read');
    return response.json();
  });
}

/** Places the order of the cart by OCC, as the storefront does. Gives the raw status and body. */
export async function placeOrderByOcc(
  customer: Customer,
  cartCode: string,
  currency: Currency
): Promise<{ status: number; body: any }> {
  const bearer = await customerToken(customer);
  return withApi(async (api) => {
    const response = await api.post(`${userPath(customer)}/orders`, {
      headers: { Authorization: `Bearer ${bearer}` },
      params: { cartId: cartCode, termsChecked: 'true', curr: currency, fields: 'FULL' },
    });
    const text = await response.text();
    return { status: response.status(), body: text ? JSON.parse(text) : undefined };
  });
}

/** Reads an order of the customer with fields=FULL, as the storefront does. */
export async function getOccOrder(customer: Customer, orderCode: string): Promise<any> {
  const bearer = await customerToken(customer);
  return withApi(async (api) => {
    const response = await expectOk(
      await api.get(`${userPath(customer)}/orders/${orderCode}`, {
        headers: { Authorization: `Bearer ${bearer}` },
        params: { fields: 'FULL' },
      }),
      'Order read'
    );
    return response.json();
  });
}

/** Reads the Stripe configuration of the base site without a token. */
export async function getStripeConfigAnonymously(): Promise<{ status: number; body: any }> {
  return withApi(async (api) => {
    const response = await api.get(`${OCC_BASE}/payments/stripe/config`);
    return { status: response.status(), body: await response.json().catch(() => undefined) };
  });
}

/** Posts a raw body to the Stripe webhook endpoint. */
export async function postWebhook(payload: string, signature?: string): Promise<{ status: number; body: string }> {
  return withApi(async (api) => {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (signature) {
      headers['Stripe-Signature'] = signature;
    }
    const response = await api.post(`${OCC_BASE}/payments/stripe/webhook`, { headers, data: payload });
    return { status: response.status(), body: await response.text() };
  });
}
