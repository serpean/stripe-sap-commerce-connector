package com.stripe.core.webhook;

import com.stripe.model.PaymentIntent;
import de.hybris.platform.task.RetryLaterException;

/**
 * Handles the {@code payment_intent.*} webhook events.
 */
public interface StripePaymentIntentEventHandler
{
	/**
	 * @param eventType     the Stripe event type, for example {@code payment_intent.succeeded}
	 * @param paymentIntent the PaymentIntent of the event; the handler reads its current state from Stripe
	 * @throws RetryLaterException when the customer can still place the order (grace period): this is not a failure
	 */
	StripeWebhookOutcome handle(String eventType, PaymentIntent paymentIntent) throws RetryLaterException;
}
