package com.stripe.core.payment;

import de.hybris.platform.core.model.order.CartModel;

/**
 * Keeps one Stripe PaymentIntent for each cart.
 */
public interface StripePaymentIntentService
{
	/**
	 * Gives the PaymentIntent of the cart. Creates it when the cart has none. Updates the amount and the currency
	 * when they changed. Replaces the PaymentIntent, and releases its money, when Stripe no longer accepts changes.
	 */
	StripeIntentData prepareForCart(CartModel cart);

	/**
	 * Gives the money of a PaymentIntent back to the customer: cancel when authorized, refund the captured amount that is
	 * not refunded yet when captured (no Stripe call when nothing is left).
	 */
	void releaseFunds(String paymentIntentId, String reason);
}
