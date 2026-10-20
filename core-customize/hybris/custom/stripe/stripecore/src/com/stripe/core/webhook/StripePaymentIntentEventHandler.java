package com.stripe.core.webhook;

import com.stripe.model.PaymentIntent;
import de.hybris.platform.task.RetryLaterException;
import java.time.Instant;

/**
 * Handles the {@code payment_intent.*} webhook events.
 */
public interface StripePaymentIntentEventHandler
{
	/**
	 * @param eventType     the Stripe event type, for example {@code payment_intent.succeeded}
	 * @param paymentIntent the PaymentIntent of the event; the handler reads its current state from Stripe
	 * @param eventCreated  the time Stripe created the event, or null when unknown; the grace period starts at the later
	 *                         of this time and the creation of the PaymentIntent record
	 * @throws RetryLaterException when the customer can still place the order (grace period): this is not a failure;
	 *            its delay is the time left
	 */
	StripeWebhookOutcome handle(String eventType, PaymentIntent paymentIntent, Instant eventCreated)
			throws RetryLaterException;
}
