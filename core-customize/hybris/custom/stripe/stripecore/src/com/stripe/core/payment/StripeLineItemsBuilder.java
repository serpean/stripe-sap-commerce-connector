package com.stripe.core.payment;

import de.hybris.platform.core.model.order.AbstractOrderModel;
import java.util.Map;

/**
 * Builds the order line data that is sent to Stripe with a PaymentIntent. The data never changes the amount to charge.
 */
public interface StripeLineItemsBuilder
{
	/**
	 * Builds extra PaymentIntent parameters ({@code amount_details}, {@code payment_details}) in the nested form that
	 * Stripe accepts. Stripe checks that {@code sum(unit_cost * quantity) + shipping + tax - discount_amount} equals the
	 * amount.
	 *
	 * @param order
	 *           cart or order
	 * @param amountMinor
	 *           amount to charge, in minor units
	 * @return the parameters, or an empty map when the lines cannot add up to the amount (a WARN is logged)
	 */
	Map<String, Object> build(AbstractOrderModel order, long amountMinor);
}
