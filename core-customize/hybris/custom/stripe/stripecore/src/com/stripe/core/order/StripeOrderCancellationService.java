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

	/**
	 * Cancels the full order and gives the money of its PaymentIntent back, also when the order has an ACCEPTED
	 * authorization: the order total is not the amount of the payment (rule one). Calling it again for the same order
	 * does nothing more.
	 */
	void cancelForAmountMismatch(OrderModel order);
}
