import { request } from '@playwright/test';

const OCC_URL = process.env['E2E_OCC_URL'] ?? 'https://localhost:9002';
const BASE_SITE = 'electronics-spa';
const CLIENT_ID = 'mobile_android';
const CLIENT_SECRET = 'secret';

export interface Customer {
  email: string;
  password: string;
}

async function clientToken(): Promise<string> {
  const api = await request.newContext({ ignoreHTTPSErrors: true });
  const response = await api.post(`${OCC_URL}/authorizationserver/oauth/token`, {
    form: { grant_type: 'client_credentials', client_id: CLIENT_ID, client_secret: CLIENT_SECRET },
  });
  if (!response.ok()) {
    throw new Error(`OAuth client token failed: ${response.status()} ${await response.text()}`);
  }
  return (await response.json()).access_token;
}

async function customerToken(customer: Customer): Promise<string> {
  const api = await request.newContext({ ignoreHTTPSErrors: true });
  const response = await api.post(`${OCC_URL}/authorizationserver/oauth/token`, {
    form: {
      grant_type: 'password',
      client_id: CLIENT_ID,
      client_secret: CLIENT_SECRET,
      username: customer.email,
      password: customer.password,
    },
  });
  if (!response.ok()) {
    throw new Error(`OAuth customer token failed: ${response.status()} ${await response.text()}`);
  }
  return (await response.json()).access_token;
}

/** Registers a new customer with a unique email, so that the test can run many times. */
export async function registerCustomer(): Promise<Customer> {
  const customer = { email: `e2e+${Date.now()}@example.com`, password: 'Stripe-e2e-1234!' };
  const api = await request.newContext({ ignoreHTTPSErrors: true });
  const response = await api.post(`${OCC_URL}/occ/v2/${BASE_SITE}/users`, {
    headers: { Authorization: `Bearer ${await clientToken()}` },
    data: {
      uid: customer.email,
      password: customer.password,
      firstName: 'E2E',
      lastName: 'Stripe',
      titleCode: 'mr',
    },
  });
  if (!response.ok()) {
    throw new Error(`Customer registration failed: ${response.status()} ${await response.text()}`);
  }
  return customer;
}

/** Adds a default US delivery address to the customer. */
export async function addDefaultAddress(customer: Customer): Promise<void> {
  const api = await request.newContext({ ignoreHTTPSErrors: true });
  const response = await api.post(
    `${OCC_URL}/occ/v2/${BASE_SITE}/users/${encodeURIComponent(customer.email)}/addresses`,
    {
      headers: { Authorization: `Bearer ${await customerToken(customer)}` },
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
    }
  );
  if (!response.ok()) {
    throw new Error(`Address creation failed: ${response.status()} ${await response.text()}`);
  }
}
