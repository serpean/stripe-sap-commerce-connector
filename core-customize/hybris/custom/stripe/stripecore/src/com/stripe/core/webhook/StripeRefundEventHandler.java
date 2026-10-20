package com.stripe.core.webhook;

import com.stripe.model.StripeObject;

/**
 * Handles the {@code charge.refunded} and {@code refund.updated} webhook events.
 */
public interface StripeRefundEventHandler
{
	/**
	 * Updates the REFUND_FOLLOW_ON entries whose requestId is the id of a refund of the object.
	 *
	 * @param object a {@code Refund}, or a {@code Charge} with its refunds
	 * @return IGNORED when no SAP refund entry matches
	 */
	StripeWebhookOutcome handle(StripeObject object);
}
