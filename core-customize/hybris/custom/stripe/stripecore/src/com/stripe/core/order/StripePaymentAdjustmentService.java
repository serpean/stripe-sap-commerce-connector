package com.stripe.core.order;

import com.stripe.model.PaymentIntent;
import de.hybris.platform.core.model.order.OrderModel;
import java.util.Optional;

/**
 * Brings the Stripe payment of an order to the order amount after a cancel: it voids or refunds the money above the
 * amount. Every method is safe to call again for the same order: the live PaymentIntent decides.
 */
public interface StripePaymentAdjustmentService
{
	/**
	 * The live PaymentIntent of a Stripe order that has the ACCEPTED authorization of it.
	 *
	 * @return empty when the order is not paid with Stripe or has no ACCEPTED authorization of its PaymentIntent
	 * @throws com.stripe.core.gateway.StripeGatewayException when Stripe cannot be read
	 */
	Optional<PaymentIntent> livePaymentIntent(OrderModel order);

	/**
	 * Adjusts the payment of the order from a PaymentIntent that was read before. A fully cancelled order has amount 0
	 * (the SAP cancel keeps the delivery cost); otherwise the amount is the Stripe charge amount of the order.
	 *
	 * @return true when there is nothing more to do; false when the refund or void was not ACCEPTED
	 */
	boolean adjust(OrderModel order, PaymentIntent paymentIntent);

	/**
	 * Reads the live PaymentIntent and adjusts the payment of the order.
	 *
	 * @return true when there is nothing more to do; false when the refund or void was not ACCEPTED
	 * @throws com.stripe.core.gateway.StripeGatewayException when Stripe cannot be read
	 */
	boolean adjustToOrderTotal(OrderModel order);

	/**
	 * Schedules a task that adjusts the payment of the order again later.
	 */
	void scheduleRetry(OrderModel order);
}
