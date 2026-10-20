import { expect, test } from '@playwright/test';
import { countWebhookEvents, getWebhookEvents } from '../support/backend';
import { postWebhook } from '../support/occ';
import { signWebhook } from '../support/stripe';

test('15. invalid signature: 400; nothing changes', async () => {
  const eventId = `evt_e2e_${Date.now()}`;
  const payload = JSON.stringify({
    id: eventId,
    object: 'event',
    type: 'payment_intent.succeeded',
    created: Math.floor(Date.now() / 1000),
    data: { object: { id: 'pi_e2e_unknown', object: 'payment_intent', status: 'succeeded' } },
  });
  const before = await countWebhookEvents();

  const forged = await postWebhook(payload, `t=${Math.floor(Date.now() / 1000)},v1=${'0'.repeat(64)}`);
  expect(forged.status).toBe(400);
  const unsigned = await postWebhook(payload);
  expect(unsigned.status).toBe(400);
  // A payload changed after signing is refused too.
  const signature = await signWebhook(payload);
  const tampered = await postWebhook(payload.replace('succeeded', 'canceled'), signature);
  expect(tampered.status).toBe(400);

  expect(await countWebhookEvents()).toBe(before);
  expect(await getWebhookEvents(eventId)).toEqual([]);

  // Control: the same payload with a valid signature is accepted (and ignored: the PaymentIntent is not ours).
  const valid = await postWebhook(payload, signature);
  expect(valid.status).toBe(200);
  expect(JSON.parse(valid.body).status).toBe('ACCEPTED');
});
