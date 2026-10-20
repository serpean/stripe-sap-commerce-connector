package com.stripe.core.payment;

/**
 * The PaymentIntent is processing at Stripe, so it can neither change nor be cancelled.
 */
public class StripePaymentInProgressException extends RuntimeException
{
	public StripePaymentInProgressException(final String paymentIntentId)
	{
		super("Stripe payment intent " + paymentIntentId + " is processing and cannot change");
	}
}
