package com.stripe.core.status;

import com.stripe.core.payment.StripePaymentMethodGroups;
import com.stripe.model.PaymentIntent;

/**
 * Single mapping of Stripe PaymentIntent statuses.
 */
public enum StripePaymentIntentStatus
{
	AUTHORIZED, CAPTURED, PROCESSING, REQUIRES_ACTION, AWAITING_FUNDS, FAILED, CANCELED;

	/**
	 * Maps the status of a PaymentIntent. A PaymentIntent that waits for a bank transfer or a voucher payment is
	 * {@link #AWAITING_FUNDS}, not {@link #REQUIRES_ACTION}.
	 */
	public static StripePaymentIntentStatus of(final PaymentIntent paymentIntent)
	{
		final StripePaymentIntentStatus status = of(paymentIntent.getStatus());
		if (status == REQUIRES_ACTION && paymentIntent.getNextAction() != null
				&& StripePaymentMethodGroups.isInstructionsAction(paymentIntent.getNextAction().getType()))
		{
			return AWAITING_FUNDS;
		}
		return status;
	}

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
