package com.stripe.core.payment.request.impl;

import com.stripe.core.payment.request.StripeRequestFactory;
import com.stripe.core.stripe.CurrencyUtils;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Default implementation of {@link StripeRequestFactory}.
 */
public class DefaultStripeRequestFactory implements StripeRequestFactory {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeRequestFactory.class);

    private CommonI18NService commonI18NService;

    @Override
    public Long convertToStripeAmount(final BigDecimal amount, final String currencyIso) {
        if (amount == null) {
            return 0L;
        }

        final int fractionDigits = getCurrencyFractionDigits(currencyIso);
        return amount.setScale(fractionDigits, RoundingMode.HALF_UP)
                .movePointRight(fractionDigits)
                .longValueExact();
    }

    @Override
    public int getCurrencyFractionDigits(final String currencyIso) {
        if (StringUtils.isBlank(currencyIso)) {
            return 2; // Default to 2 decimal places
        }

        // Use CurrencyUtils if available, otherwise use Java Currency
        try {
            return CurrencyUtils.resolveFractionDigits(currencyIso);
        } catch (final Exception e) {
            LOG.debug("Could not resolve fraction digits from CurrencyUtils, using Java Currency");
            try {
                return Currency.getInstance(currencyIso.toUpperCase()).getDefaultFractionDigits();
            } catch (final IllegalArgumentException ex) {
                LOG.warn("Unknown currency: {}, defaulting to 2 decimal places", currencyIso);
                return 2;
            }
        }
    }

    // Setter for Spring injection

    public void setCommonI18NService(final CommonI18NService commonI18NService) {
        this.commonI18NService = commonI18NService;
    }
}
