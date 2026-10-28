package com.stripe.core.payment;

import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import java.util.Set;

/**
 * Groups of Stripe payment method types and next action types that need special handling.
 */
public final class StripePaymentMethodGroups
{
	private static final Set<String> DEFERRED_METHODS = Set.of("multibanco", "customer_balance");
	private static final String BANK_TRANSFER = "customer_balance";
	private static final Set<String> INSTRUCTIONS_ACTIONS = Set.of("multibanco_display_details",
			"display_bank_transfer_instructions");

	private StripePaymentMethodGroups()
	{
		// static helpers only
	}

	/**
	 * @return true if the customer pays outside the checkout, after the order is placed
	 */
	public static boolean isDeferred(final String paymentMethodType)
	{
		return paymentMethodType != null && DEFERRED_METHODS.contains(paymentMethodType);
	}

	/**
	 * @return true if the next action shows payment instructions instead of a redirect or an SDK step
	 */
	public static boolean isInstructionsAction(final String nextActionType)
	{
		return nextActionType != null && INSTRUCTIONS_ACTIONS.contains(nextActionType);
	}

	/**
	 * A bank transfer PaymentIntent shows bank transfer instructions, has a {@code customer_balance} payment method
	 * (expanded), or its charge was paid from the customer balance. The list of enabled payment method types is not
	 * used: with automatic payment methods it names every method.
	 */
	public static boolean isBankTransfer(final PaymentIntent intent)
	{
		if (intent.getNextAction() != null && "display_bank_transfer_instructions".equals(intent.getNextAction().getType()))
		{
			return true;
		}
		if (intent.getPaymentMethodObject() != null && BANK_TRANSFER.equals(intent.getPaymentMethodObject().getType()))
		{
			return true;
		}
		final Charge charge = intent.getLatestChargeObject();
		return charge != null && charge.getPaymentMethodDetails() != null
				&& BANK_TRANSFER.equals(charge.getPaymentMethodDetails().getType());
	}
}
