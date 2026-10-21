package com.stripe.core.webhook;

/**
 * Result of the handling of a Stripe webhook event.
 */
public enum StripeWebhookOutcome
{
	/** The event changed, or confirmed, the SAP data. */
	PROCESSED,
	/** The event is not for this shop, or it has no effect. */
	IGNORED
}
