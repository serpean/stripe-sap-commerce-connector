package com.stripe.core.payment;

/**
 * The PaymentIntent cannot change now: it is processing at Stripe, or an order is placed (or being placed) for it.
 */
public class StripePaymentInProgressException extends RuntimeException
{
	public StripePaymentInProgressException(final String paymentIntentId)
	{
		this(paymentIntentId, "it is processing");
	}

	/**
	 * @param reason why the PaymentIntent cannot change now, for the log
	 */
	public StripePaymentInProgressException(final String paymentIntentId, final String reason)
	{
		super("Stripe payment intent " + paymentIntentId + " cannot change: " + reason);
	}
}
