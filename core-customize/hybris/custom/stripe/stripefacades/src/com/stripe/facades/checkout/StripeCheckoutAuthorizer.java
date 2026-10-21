package com.stripe.facades.checkout;

import de.hybris.platform.core.model.order.CartModel;

/**
 * Authorizes the payment of a cart that has a Stripe payment info, before the order is placed.
 */
public interface StripeCheckoutAuthorizer
{
	/**
	 * Checks the PaymentIntent of the cart against the cart, takes the order placement lock and records the payment.
	 *
	 * @return {@code true} when the order can be placed
	 * @throws StripeCheckoutException when the amount is different, the payment is missing, the payment failed, needs
	 *            an action of the customer or is cancelled, an order is already placed (or being placed) for the
	 *            PaymentIntent, or the authorization could not finish (UNAVAILABLE: the payment is kept)
	 */
	boolean authorize(CartModel cart);
}
