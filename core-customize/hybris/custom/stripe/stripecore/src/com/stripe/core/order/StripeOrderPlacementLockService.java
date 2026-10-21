package com.stripe.core.order;

/**
 * Lets only one order placement run for one Stripe PaymentIntent. The lock stays after a successful placement: it
 * marks that an order exists, or is being placed, for the PaymentIntent.
 */
public interface StripeOrderPlacementLockService
{
	/**
	 * Takes the lock of the PaymentIntent.
	 *
	 * @return {@code false} when another placement holds the lock
	 */
	boolean tryAcquire(String paymentIntentId);

	/**
	 * Gives back the lock of the PaymentIntent. Use it only when the order placement failed.
	 */
	void release(String paymentIntentId);
}
