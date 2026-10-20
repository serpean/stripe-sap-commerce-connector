package com.stripe.core.order;

import com.stripe.model.PaymentIntent;
import de.hybris.platform.core.model.order.OrderModel;

import java.util.Optional;

/**
 * Places the SAP order of a paid PaymentIntent from the server, when the customer did not come back to place it.
 */
public interface StripeOrderPlacementService
{
	/**
	 * Places the order of the cart of the PaymentIntent and links it to the record of the PaymentIntent.
	 *
	 * @return the placed order; empty when no order was placed: the funds were released (no cart, amount mismatch) or
	 *         another path holds the placement lock
	 * @throws RuntimeException when the placement failed and a retry can help. The webhook runner commits the attempt
	 *            counter in the same transaction, so the caller looks for a live order before it calls this method again.
	 */
	Optional<OrderModel> placeOrderFor(PaymentIntent paymentIntent);
}
