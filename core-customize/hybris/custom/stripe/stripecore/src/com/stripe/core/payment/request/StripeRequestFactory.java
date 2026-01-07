package com.stripe.core.payment.request;

import java.math.BigDecimal;

/**
 * Factory for creating Stripe API request parameters.
 */
public interface StripeRequestFactory {

    /**
     * Converts a BigDecimal amount to Stripe's smallest currency unit (e.g., cents).
     *
     * @param amount      the amount in major currency units
     * @param currencyIso the ISO currency code
     * @return the amount in smallest currency units
     */
    Long convertToStripeAmount(BigDecimal amount, String currencyIso);

    /**
     * Gets the number of decimal places for a currency.
     *
     * @param currencyIso the ISO currency code
     * @return the number of decimal places
     */
    int getCurrencyFractionDigits(String currencyIso);
}
