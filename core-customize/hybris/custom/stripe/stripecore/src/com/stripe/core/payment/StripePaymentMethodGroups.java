package com.stripe.core.payment;

import java.util.Set;

/**
 * Groups of Stripe payment method types and next action types that need special handling.
 */
public final class StripePaymentMethodGroups
{
	private static final Set<String> DEFERRED_METHODS = Set.of("multibanco", "customer_balance");
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
}
