package com.stripe.core.payment;

import de.hybris.platform.core.model.order.OrderModel;

import java.util.Optional;

/**
 * Links SAP orders to the record of their Stripe PaymentIntent.
 */
public interface StripePaymentIntentRecordService
{
	/**
	 * The live order (not an order history snapshot) of the PaymentIntent: the order of its AUTHORIZATION transaction
	 * or, for a payment without authorization (for example, a processing one), the order whose Stripe payment info has
	 * the PaymentIntent id.
	 */
	Optional<OrderModel> findLiveOrder(String paymentIntentId);

	/**
	 * Sets the order on the record of the PaymentIntent (creates the record when it is missing). When the record is
	 * still CREATED, sets its state from the status of the PaymentIntent at Stripe.
	 */
	void linkOrder(String paymentIntentId, OrderModel order);
}
