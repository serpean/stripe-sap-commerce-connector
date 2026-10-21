package com.stripe.core.amount;

import de.hybris.platform.core.model.order.AbstractOrderModel;
import java.math.BigDecimal;

/**
 * Single place that converts between SAP Commerce amounts and Stripe minor units.
 */
public interface StripeAmountConverter
{
	/**
	 * Converts an amount to the smallest unit of the currency, rounding HALF_UP.
	 */
	long toMinorUnits(BigDecimal amount, String currencyIso);

	/**
	 * Converts an amount in the smallest unit of the currency to a decimal amount.
	 */
	BigDecimal fromMinorUnits(long amount, String currencyIso);

	/**
	 * Amount to charge in Stripe for an order or cart: {@code totalPrice}, plus {@code totalTax} when the order is net.
	 *
	 * @throws IllegalArgumentException when the order has no total price
	 */
	BigDecimal chargeAmount(AbstractOrderModel order);

	/**
	 * {@link #chargeAmount(AbstractOrderModel)} in minor units of the currency of the order.
	 */
	long chargeAmountMinor(AbstractOrderModel order);
}
