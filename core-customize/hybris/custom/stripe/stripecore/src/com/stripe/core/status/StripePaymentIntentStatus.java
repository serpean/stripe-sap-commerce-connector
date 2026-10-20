package com.stripe.core.status;

/**
 * Single mapping of Stripe PaymentIntent statuses.
 */
public enum StripePaymentIntentStatus
{
	AUTHORIZED, CAPTURED, PROCESSING, REQUIRES_ACTION, FAILED, CANCELED;

	public static StripePaymentIntentStatus of(final String stripeStatus)
	{
		if (stripeStatus != null)
		{
			switch (stripeStatus)
			{
				case "requires_capture":
					return AUTHORIZED;
				case "succeeded":
					return CAPTURED;
				case "processing":
					return PROCESSING;
				case "requires_action":
				case "requires_confirmation":
					return REQUIRES_ACTION;
				case "requires_payment_method":
					return FAILED;
				case "canceled":
					return CANCELED;
				default:
					break;
			}
		}
		throw new IllegalArgumentException("Unknown Stripe PaymentIntent status: " + stripeStatus);
	}
}
