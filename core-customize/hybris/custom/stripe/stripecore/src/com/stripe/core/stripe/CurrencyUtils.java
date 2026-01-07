package com.stripe.core.stripe;

import java.util.Currency;

public final class CurrencyUtils
{
    private CurrencyUtils()
    {
        // utility
    }

    public static int resolveFractionDigits(final String currencyIso)
    {
        if (currencyIso == null)
        {
            return 2;
        }
        try
        {
            final Currency currency = Currency.getInstance(currencyIso.toUpperCase());
            final int fractionDigits = currency.getDefaultFractionDigits();
            return fractionDigits < 0 ? 2 : fractionDigits;
        }
        catch (IllegalArgumentException ex)
        {
            return 2;
        }
    }
}
