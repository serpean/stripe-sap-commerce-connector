import { APIRequestContext } from '@playwright/test';
import { Customer, OCC_URL, getOccOrder, withApi } from './occ';

/*
 * SAP Commerce state and back-office operations for the e2e tests.
 *
 * Reads use HAC FlexibleSearch. The back-office operations (ship, cancel, return) call the same OMS facades as the
 * ordermanagementwebservices and warehousingwebservices endpoints (omsOrderFacade, omsReturnFacade,
 * warehousingConsignmentFacade, warehousingReturnFacade). They run through the HAC Groovy console because the reference
 * data has no customer support agent with a known password for an OAuth token.
 */

const HAC_URL = process.env['E2E_HAC_URL'] ?? OCC_URL;
const HAC_USER = process.env['E2E_HAC_USER'] ?? 'admin';
const HAC_PASSWORD = process.env['E2E_HAC_PASSWORD'] ?? 'nimda';

function csrfOf(html: string, pattern: RegExp, what: string): string {
  const match = html.match(pattern);
  if (!match) {
    throw new Error(`No CSRF token in the HAC ${what} page`);
  }
  return match[1];
}

/** Logs in to the HAC with the CSRF flow and gives the CSRF token of the console page. */
async function hacSession(api: APIRequestContext, consolePath: string): Promise<string> {
  const login = await (await api.get(`${HAC_URL}/login`)).text();
  const loginCsrf = csrfOf(login, /name="_csrf"\s+value="([^"]+)"/, 'login');
  await api.post(`${HAC_URL}/j_spring_security_check`, {
    form: { j_username: HAC_USER, j_password: HAC_PASSWORD, _csrf: loginCsrf },
  });
  const page = await (await api.get(`${HAC_URL}${consolePath}`)).text();
  return csrfOf(page, /name="_csrf"\s+content="([^"]+)"/, consolePath);
}

/** Runs a FlexibleSearch query in the HAC and gives the rows (all values as strings or null). */
export async function flexibleSearch(query: string): Promise<(string | null)[][]> {
  return withApi(async (api) => {
    const csrf = await hacSession(api, '/console/flexsearch');
    const response = await api.post(`${HAC_URL}/console/flexsearch/execute`, {
      headers: { 'X-CSRF-TOKEN': csrf },
      form: { flexibleSearchQuery: query, maxCount: '1000', user: HAC_USER, locale: 'en', commit: 'false' },
    });
    const body = await response.json();
    if (body.exception) {
      throw new Error(`FlexibleSearch failed: ${query}\n${JSON.stringify(body.exception).slice(0, 500)}`);
    }
    return body.resultList;
  });
}

/** Runs a Groovy script in the HAC and gives its printed output. With commit=false the changes are rolled back. */
export async function groovy(script: string, commit: boolean): Promise<string> {
  return withApi(async (api) => {
    const csrf = await hacSession(api, '/console/scripting');
    const response = await api.post(`${HAC_URL}/console/scripting/execute`, {
      headers: { 'X-CSRF-TOKEN': csrf },
      form: { script, scriptType: 'groovy', commit: String(commit) },
    });
    const body = await response.json();
    if (body.stacktraceText) {
      throw new Error(`Groovy script failed: ${body.stacktraceText.slice(0, 1500)}`);
    }
    return body.outputText as string;
  });
}

/** Quotes a value for a FlexibleSearch literal. The values are codes and ids that the tests create. */
function literal(value: string): string {
  if (!/^[\w@.+:-]+$/.test(value)) {
    throw new Error(`Unexpected characters in query value ${value}`);
  }
  return `'${value}'`;
}

export async function waitFor<T>(
  probe: () => Promise<T | undefined | null | false>,
  timeoutMs: number,
  what: string,
  intervalMs = 2_000
): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;
  while (Date.now() < deadline) {
    try {
      const value = await probe();
      if (value) {
        return value;
      }
    } catch (error) {
      lastError = error;
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(`Timed out after ${timeoutMs} ms waiting for ${what}${lastError ? `: ${lastError}` : ''}`);
}

export interface PaymentEntry {
  code: string;
  type: string;
  status: string;
  amount: number;
  requestId: string;
}

/** The payment transaction entries of the order (not of its history snapshots), oldest first. */
export async function getPaymentEntries(orderCode: string): Promise<PaymentEntry[]> {
  const rows = await flexibleSearch(
    'SELECT {e.code}, {t.code}, {e.transactionStatus}, {e.amount}, {e.requestId} FROM {PaymentTransactionEntry AS e ' +
      'JOIN PaymentTransactionType AS t ON {e.type} = {t.pk} ' +
      'JOIN PaymentTransaction AS pt ON {e.paymentTransaction} = {pt.pk} ' +
      'JOIN Order AS o ON {pt.order} = {o.pk}} ' +
      `WHERE {o.code} = ${literal(orderCode)} AND {o.versionID} IS NULL ORDER BY {e.creationtime}, {e.pk}`
  );
  return rows.map(([code, type, status, amount, requestId]) => ({
    code: code ?? '',
    type: type ?? '',
    status: status ?? '',
    amount: Number(amount),
    requestId: requestId ?? '',
  }));
}

export interface SapOrder {
  code: string;
  status: string;
  currency: string;
  totalPrice: number;
  deliveryCost: number;
  paymentIntentId: string | null;
}

/** The SAP order (not a history snapshot), or undefined. */
export async function getSapOrder(orderCode: string): Promise<SapOrder | undefined> {
  const rows = await flexibleSearch(
    'SELECT {o.code}, {s.code}, {c.isocode}, {o.totalPrice}, {o.deliveryCost}, {i.stripePaymentIntentId} FROM {Order AS o ' +
      'LEFT JOIN OrderStatus AS s ON {o.status} = {s.pk} ' +
      'LEFT JOIN Currency AS c ON {o.currency} = {c.pk} ' +
      'LEFT JOIN StripePaymentInfo AS i ON {o.paymentInfo} = {i.pk}} ' +
      `WHERE {o.code} = ${literal(orderCode)} AND {o.versionID} IS NULL`
  );
  if (!rows.length) {
    return undefined;
  }
  const [code, status, currency, total, delivery, paymentIntentId] = rows[0];
  return {
    code: code ?? '',
    status: status ?? '',
    currency: currency ?? '',
    totalPrice: Number(total),
    deliveryCost: Number(delivery),
    paymentIntentId,
  };
}

/** Waits until the order has the status (for example READY after the OMS sourcing, or COMPLETED). */
export function waitForOrderStatus(orderCode: string, status: string, timeoutMs = 120_000): Promise<SapOrder> {
  return waitFor(async () => {
    const order = await getSapOrder(orderCode);
    return order?.status === status ? order : undefined;
  }, timeoutMs, `order ${orderCode} in status ${status}`);
}

/** Waits until the order has a payment entry of the type (a name or a pattern), and gives all entries. */
export function waitForPaymentEntry(orderCode: string, type: string | RegExp, timeoutMs = 120_000): Promise<PaymentEntry[]> {
  const matches = (entryType: string) => (typeof type === 'string' ? entryType === type : type.test(entryType));
  return waitFor(async () => {
    const entries = await getPaymentEntries(orderCode);
    return entries.some((e) => matches(e.type)) ? entries : undefined;
  }, timeoutMs, `a ${type} entry on order ${orderCode}`);
}

/** The codes of the orders (not history snapshots) that are paid with the PaymentIntent. */
export async function getOrderCodesForPaymentIntent(paymentIntentId: string): Promise<string[]> {
  const rows = await flexibleSearch(
    'SELECT DISTINCT {o.code} FROM {Order AS o JOIN StripePaymentInfo AS i ON {o.paymentInfo} = {i.pk}} ' +
      `WHERE {i.stripePaymentIntentId} = ${literal(paymentIntentId)} AND {o.versionID} IS NULL`
  );
  return rows.map(([code]) => code ?? '');
}

/** The codes of the orders (not history snapshots) of the customer. */
export async function getOrderCodesOfCustomer(customer: Customer): Promise<string[]> {
  const rows = await flexibleSearch(
    'SELECT {o.code} FROM {Order AS o JOIN User AS u ON {o.user} = {u.pk}} ' +
      `WHERE {u.uid} = ${literal(customer.email)} AND {o.versionID} IS NULL ORDER BY {o.code}`
  );
  return rows.map(([code]) => code ?? '');
}

/** The stock the shop can still sell for the product: available minus reserved, summed over all stock levels. */
export async function getAvailableStock(productCode: string): Promise<number> {
  const rows = await flexibleSearch(
    `SELECT SUM({s.available} - {s.reserved}) FROM {StockLevel AS s} WHERE {s.productCode} = ${literal(productCode)}`
  );
  return Number(rows[0]?.[0] ?? 0);
}

/** Stock that every test product keeps after the refill: available minus reserved. */
export const MIN_TEST_STOCK = 500;

/**
 * Refills the stock of the products for the e2e tests in the local reference platform. Each StockLevel of the products
 * gets available = reserved + MIN_TEST_STOCK when it has less; available is never lowered. A forced out-of-stock level
 * goes back to the default status. Idempotent. Gives one line per change: product, warehouse, old and new values.
 */
export async function refillTestStock(productCodes: string[]): Promise<string[]> {
  const codes = [...new Set(productCodes)];
  codes.forEach(literal);
  if (!codes.length) {
    return [];
  }
  const output = await groovy(
    `import de.hybris.platform.basecommerce.enums.InStockStatus
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery
def query = new FlexibleSearchQuery("SELECT {pk} FROM {StockLevel} WHERE {productCode} IN (?codes)")
query.addQueryParameter('codes', ${JSON.stringify(codes)})
flexibleSearchService.search(query).result.each { level ->
  def oldAvailable = level.available
  def oldStatus = level.inStockStatus
  def changed = false
  if (level.available - level.reserved < ${MIN_TEST_STOCK}) {
    level.available = level.reserved + ${MIN_TEST_STOCK}
    changed = true
  }
  if (InStockStatus.FORCEOUTOFSTOCK == level.inStockStatus) {
    level.inStockStatus = InStockStatus.NOTSPECIFIED
    changed = true
  }
  if (changed) {
    modelService.save(level)
    println "\${level.productCode} \${level.warehouse?.code}: available \${oldAvailable} -> \${level.available}, reserved \${level.reserved}, status \${oldStatus} -> \${level.inStockStatus}"
  }
}`,
    true
  );
  return output.split('\n').map((line) => line.trim()).filter(Boolean);
}

export interface PaymentIntentRecord {
  paymentIntentId: string;
  cartCode: string;
  amount: number;
  currency: string;
  state: string;
  orderCode: string | null;
}

/** The connector's record of the PaymentIntent, or undefined. */
export async function getPaymentIntentRecord(paymentIntentId: string): Promise<PaymentIntentRecord | undefined> {
  const rows = await flexibleSearch(
    'SELECT {r.paymentIntentId}, {r.cartCode}, {r.amount}, {r.currencyIso}, {s.code}, {o.code} FROM {StripePaymentIntentRecord AS r ' +
      'LEFT JOIN StripePaymentIntentState AS s ON {r.state} = {s.pk} ' +
      'LEFT JOIN Order AS o ON {r.order} = {o.pk}} ' +
      `WHERE {r.paymentIntentId} = ${literal(paymentIntentId)}`
  );
  if (!rows.length) {
    return undefined;
  }
  const [id, cartCode, amount, currency, state, orderCode] = rows[0];
  return {
    paymentIntentId: id ?? '',
    cartCode: cartCode ?? '',
    amount: Number(amount),
    currency: currency ?? '',
    state: state ?? '',
    orderCode,
  };
}

/** The records of all PaymentIntents of a cart, oldest first. */
export async function getPaymentIntentRecordsOfCart(cartCode: string): Promise<PaymentIntentRecord[]> {
  const rows = await flexibleSearch(
    `SELECT {r.paymentIntentId} FROM {StripePaymentIntentRecord AS r} WHERE {r.cartCode} = ${literal(cartCode)} ` +
      'ORDER BY {r.creationtime}, {r.pk}'
  );
  const records: PaymentIntentRecord[] = [];
  for (const [id] of rows) {
    const record = await getPaymentIntentRecord(id ?? '');
    if (record) {
      records.push(record);
    }
  }
  return records;
}

export interface WebhookEvent {
  eventId: string;
  type: string;
  state: string;
  attempts: number;
}

/** The stored webhook events with the id (the event id has a unique index, so there is zero or one). */
export async function getWebhookEvents(eventId: string): Promise<WebhookEvent[]> {
  const rows = await flexibleSearch(
    'SELECT {w.eventId}, {w.type}, {s.code}, {w.attempts} FROM {StripeWebhookEvent AS w ' +
      'LEFT JOIN StripeWebhookEventState AS s ON {w.state} = {s.pk}} ' +
      `WHERE {w.eventId} = ${literal(eventId)}`
  );
  return rows.map(([id, type, state, attempts]) => ({
    eventId: id ?? '',
    type: type ?? '',
    state: state ?? '',
    attempts: Number(attempts),
  }));
}

/** The number of stored webhook events, to check that a rejected call stores nothing. */
export async function countWebhookEvents(): Promise<number> {
  const rows = await flexibleSearch('SELECT COUNT({pk}) FROM {StripeWebhookEvent}');
  return Number(rows[0][0]);
}

/** Reads an order of the customer by OCC (fields=FULL), as the storefront does. */
export function getOrder(customer: Customer, orderCode: string): Promise<any> {
  return getOccOrder(customer, orderCode);
}

const FIND_ORDER = (orderCode: string) => `
def order = flexibleSearchService.search(
    "SELECT {pk} FROM {Order} WHERE {code} = ?code AND {versionID} IS NULL", [code: ${JSON.stringify(orderCode)}]).result[0]
`;

/** Waits until OMS has sourced the order, and confirms the shipping of every consignment. */
export async function shipOrder(orderCode: string): Promise<void> {
  await waitFor(
    async () =>
      (await groovy(`${FIND_ORDER(orderCode)}
println order.consignments.any { it.status?.code == 'READY' } ? 'ready' : ''`, false)).trim() === 'ready',
    120_000,
    `the consignments of order ${orderCode}`
  );
  await groovy(
    `${FIND_ORDER(orderCode)}
order.consignments.findAll { it.status?.code == 'READY' }.each { warehousingConsignmentFacade.confirmShipConsignment(it.code) }`,
    true
  );
}

/**
 * Cancels the order with the OMS cancellation (omsOrderFacade.createRequestOrderCancel). Without a quantity, every
 * entry is cancelled completely.
 */
export async function cancelOrder(orderCode: string, partial?: { entryNumber: number; quantity: number }): Promise<void> {
  await groovy(
    `import de.hybris.platform.basecommerce.enums.CancelReason
import de.hybris.platform.ordermanagementfacades.cancellation.data.OrderCancelEntryData
import de.hybris.platform.ordermanagementfacades.cancellation.data.OrderCancelRequestData
${FIND_ORDER(orderCode)}
def request = new OrderCancelRequestData()
request.orderCode = order.code
request.userId = order.user.uid
def entries = order.entries${partial ? `.findAll { it.entryNumber == ${partial.entryNumber} }` : ''}
request.entries = entries.collect { entry ->
  def cancel = new OrderCancelEntryData()
  cancel.orderEntryNumber = entry.entryNumber
  cancel.cancelQuantity = ${partial ? `${partial.quantity}L` : 'entry.quantity'}
  cancel.cancelReason = CancelReason.CUSTOMERREQUEST
  cancel
}
omsOrderFacade.createRequestOrderCancel(request)`,
    true
  );
}

/** Waits until the return process waits at the step. An event sent before the step is reached is lost. */
async function waitForReturnStep(code: string, step: string): Promise<void> {
  await waitFor(
    async () =>
      (await groovy(
        `def r = flexibleSearchService.search("SELECT {pk} FROM {ReturnRequest} WHERE {code} = ?code", [code: ${JSON.stringify(code)}]).result[0]
println r.returnProcess.any { p -> p.currentTasks.any { it.action == ${JSON.stringify(step)} } } ? 'waiting' : ''`,
        false
      )).trim() === 'waiting',
    120_000,
    `return ${code} at step ${step}`
  );
}

/**
 * Creates a return of every entry of the shipped order (omsReturnFacade.createReturnRequest), approves it, and accepts
 * the goods (warehousingReturnFacade.acceptGoods), so that the return process reaches the refund. Gives the return code.
 */
export async function createReturn(orderCode: string): Promise<string> {
  const code = (
    await groovy(
      `import de.hybris.platform.basecommerce.enums.RefundReason
import de.hybris.platform.basecommerce.enums.ReturnAction
import de.hybris.platform.commercefacades.order.data.OrderData
import de.hybris.platform.commercefacades.order.data.OrderEntryData
import de.hybris.platform.ordermanagementfacades.returns.data.ReturnEntryData
import de.hybris.platform.ordermanagementfacades.returns.data.ReturnRequestData
${FIND_ORDER(orderCode)}
def request = new ReturnRequestData()
request.order = new OrderData(code: order.code)
request.refundDeliveryCost = false
request.returnEntries = order.entries.collect { entry ->
  def returnEntry = new ReturnEntryData()
  returnEntry.orderEntry = new OrderEntryData(entryNumber: entry.entryNumber)
  returnEntry.expectedQuantity = entry.quantity
  returnEntry.action = ReturnAction.HOLD
  returnEntry.refundReason = RefundReason.DAMAGEDINTRANSIT
  returnEntry
}
def created = omsReturnFacade.createReturnRequest(request)
println created.code`,
      true
    )
  ).trim();
  await waitForReturnStep(code, 'waitForConfirmOrCancelReturnAction');
  await groovy(`omsReturnFacade.approveReturnRequest(${JSON.stringify(code)})`, true);
  await waitForReturnStep(code, 'waitForGoodsAction');
  await groovy(`warehousingReturnFacade.acceptGoods(${JSON.stringify(code)})`, true);
  return code;
}

/** Changes a configuration property in the running server (not persisted; a restart reads local.properties again). */
export async function setConfig(key: string, value: string): Promise<void> {
  await groovy(
    `configurationService.configuration.setProperty(${JSON.stringify(key)}, ${JSON.stringify(value)})
println configurationService.configuration.getString(${JSON.stringify(key)})`,
    true
  );
}

export async function getConfig(key: string): Promise<string> {
  return (await groovy(`println configurationService.configuration.getString(${JSON.stringify(key)}, '')`, false)).trim();
}
