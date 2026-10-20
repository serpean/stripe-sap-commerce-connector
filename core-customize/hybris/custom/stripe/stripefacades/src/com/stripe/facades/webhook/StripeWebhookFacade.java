package com.stripe.facades.webhook;

/**
 * Receives Stripe webhook calls. It checks the signature, stores the event once and schedules its processing. It never
 * processes the event in the calling thread.
 */
public interface StripeWebhookFacade
{
	/**
	 * @param payload
	 *           raw body of the call
	 * @param signatureHeader
	 *           value of the Stripe-Signature header, can be null
	 * @return the receipt of the call
	 */
	StripeWebhookReceipt receive(String payload, String signatureHeader);
}
