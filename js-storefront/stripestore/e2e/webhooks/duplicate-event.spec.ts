import { expect, test } from '@playwright/test';
import { expectOneAuthorizedOrder } from '../support/assertions';
import { getWebhookEvents, waitFor } from '../support/backend';
import { placeOrderWithCard } from '../support/checkout';
import { postWebhook } from '../support/occ';
import { findEvents, retrieveEvent, signWebhook, triggerResend } from '../support/stripe';

test('16. duplicate event: processed once', async ({ page }) => {
  const { orderCode } = await placeOrderWithCard(page, { currency: 'USD' });
  const { intent } = await expectOneAuthorizedOrder(orderCode);
  const [event] = await waitFor(async () => {
    const events = await findEvents('payment_intent.amount_capturable_updated', intent.id);
    return events.length ? events : undefined;
  }, 60_000, 'the payment_intent.amount_capturable_updated event');
  const [processed] = await waitFor(async () => {
    const stored = await getWebhookEvents(event.id);
    return stored[0]?.state === 'PROCESSED' ? stored : undefined;
  }, 60_000, 'the first processing of the event');

  // Stripe sends the event again (twice), and the same signed event arrives twice at the endpoint.
  await triggerResend(event.id);
  await triggerResend(event.id);
  const payload = JSON.stringify(await retrieveEvent(event.id));
  const signature = await signWebhook(payload);
  const answers = [await postWebhook(payload, signature), await postWebhook(payload, signature)];
  for (const answer of answers) {
    expect(answer.status).toBe(200);
    expect(JSON.parse(answer.body).status).toBe('DUPLICATE');
  }
  await new Promise((resolve) => setTimeout(resolve, 10_000));

  // One stored event, processed once; still one order with one authorization.
  expect(await getWebhookEvents(event.id)).toEqual([processed]);
  await expectOneAuthorizedOrder(orderCode);
});
