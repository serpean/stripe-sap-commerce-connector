package com.stripe.core.order;

import de.hybris.platform.core.model.order.OrderModel;

/**
 * Cancels the orders whose Stripe payment failed.
 */
public interface StripeOrderCancellationService
{
	/**
	 * Cancels the full order because its payment failed. Calling it again for the same order does nothing.
	 */
	void cancelForFailedPayment(OrderModel order);
}
