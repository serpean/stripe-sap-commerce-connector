package com.stripe.core.payment;

/**
 * What the storefront needs to confirm a PaymentIntent.
 */
public class StripeIntentData
{
	private final String paymentIntentId;
	private final String clientSecret;
	private final String status;

	public StripeIntentData(final String paymentIntentId, final String clientSecret, final String status)
	{
		this.paymentIntentId = paymentIntentId;
		this.clientSecret = clientSecret;
		this.status = status;
	}

	public String getPaymentIntentId()
	{
		return paymentIntentId;
	}

	public String getClientSecret()
	{
		return clientSecret;
	}

	public String getStatus()
	{
		return status;
	}
}
