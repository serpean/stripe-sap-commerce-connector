package com.stripe.core.amount;

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
}
