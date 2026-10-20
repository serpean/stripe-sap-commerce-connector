package com.stripe.facades.checkout;

/**
 * Stops a Stripe checkout. The {@link Reason} is the stable code that the storefront API gives to the client. The
 * message is for the log only.
 */
public class StripeCheckoutException extends RuntimeException
{
	public enum Reason
	{
		AMOUNT_MISMATCH, PAYMENT_FAILED, PAYMENT_NOT_FOUND, ALREADY_PLACED
	}

	private final Reason reason;

	public StripeCheckoutException(final Reason reason, final String message)
	{
		super(message);
		this.reason = reason;
	}

	public Reason getReason()
	{
		return reason;
	}
}
