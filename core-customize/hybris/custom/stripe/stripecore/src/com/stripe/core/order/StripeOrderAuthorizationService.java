package com.stripe.core.order;

import com.stripe.model.PaymentIntent;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.order.OrderModel;

/**
 * Authorizes an order whose payment was pending when it was placed: it has a Stripe payment info but no AUTHORIZATION
 * entry.
 */
public interface StripeOrderAuthorizationService
{
	/**
	 * @return true when the cart or order has an ACCEPTED AUTHORIZATION entry whose requestId is the PaymentIntent id
	 */
	boolean hasAcceptedAuthorization(AbstractOrderModel order, String paymentIntentId);

	/**
	 * Authorizes the Stripe charge amount of the order against the PaymentIntent, as the checkout does for a cart. Does
	 * nothing when the order already has an ACCEPTED authorization of the PaymentIntent.
	 *
	 * @return true when the order has an ACCEPTED authorization at the end; false when the authorization was rejected
	 *         (for example, the amount of the PaymentIntent is not the amount of the order)
	 * @throws IllegalStateException when the authorization ended in ERROR (Stripe not reachable): a retry can help
	 */
	boolean authorizePendingOrder(OrderModel order, PaymentIntent paymentIntent);
}
