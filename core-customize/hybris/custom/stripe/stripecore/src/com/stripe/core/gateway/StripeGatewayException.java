package com.stripe.core.gateway;

/**
 * Error from a call to Stripe. The message never holds keys or card data.
 */
public class StripeGatewayException extends RuntimeException
{
	private final String stripeCode;
	private final boolean retryable;
	private final boolean signatureInvalid;

	public StripeGatewayException(final String message, final String stripeCode, final boolean retryable,
			final boolean signatureInvalid, final Throwable cause)
	{
		super(message, cause);
		this.stripeCode = stripeCode;
		this.retryable = retryable;
		this.signatureInvalid = signatureInvalid;
	}

	public String getStripeCode()
	{
		return stripeCode;
	}

	public boolean isRetryable()
	{
		return retryable;
	}

	public boolean isSignatureInvalid()
	{
		return signatureInvalid;
	}
}
