package com.stripe.facades.webhook;

/**
 * Result of the intake of a Stripe webhook call.
 */
public enum StripeWebhookReceipt
{
	ACCEPTED, DUPLICATE, INVALID_SIGNATURE
}
