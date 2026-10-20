package com.stripe.core.amount.impl;

import com.stripe.core.amount.StripeAmountConverter;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import org.apache.commons.lang3.StringUtils;

/**
 * Uses {@code CurrencyModel.digits} from SAP Commerce. If SAP has no value, uses {@link Currency}.
 */
public class DefaultStripeAmountConverter implements StripeAmountConverter
{
	private CommonI18NService commonI18NService;

	@Override
	public long toMinorUnits(final BigDecimal amount, final String currencyIso)
	{
		if (amount == null)
		{
			throw new IllegalArgumentException("Amount is required");
		}
		final int digits = resolveDigits(currencyIso);
		return amount.setScale(digits, RoundingMode.HALF_UP).movePointRight(digits).longValueExact();
	}

	@Override
	public BigDecimal fromMinorUnits(final long amount, final String currencyIso)
	{
		return BigDecimal.valueOf(amount, resolveDigits(currencyIso));
	}

	@Override
	public BigDecimal chargeAmount(final AbstractOrderModel order)
	{
		if (order.getTotalPrice() == null)
		{
			throw new IllegalArgumentException("Order " + order.getCode() + " has no total price");
		}
		final BigDecimal total = BigDecimal.valueOf(order.getTotalPrice());
		if (Boolean.TRUE.equals(order.getNet()) && order.getTotalTax() != null)
		{
			return total.add(BigDecimal.valueOf(order.getTotalTax()));
		}
		return total;
	}

	@Override
	public long chargeAmountMinor(final AbstractOrderModel order)
	{
		if (order.getCurrency() == null)
		{
			throw new IllegalArgumentException("Order " + order.getCode() + " has no currency");
		}
		return toMinorUnits(chargeAmount(order), order.getCurrency().getIsocode());
	}

	protected int resolveDigits(final String currencyIso)
	{
		if (StringUtils.isBlank(currencyIso))
		{
			throw new IllegalArgumentException("Currency is required");
		}
		final String iso = currencyIso.toUpperCase();
		final Integer sapDigits = findSapDigits(iso);
		if (sapDigits != null)
		{
			return sapDigits.intValue();
		}
		final int javaDigits = Currency.getInstance(iso).getDefaultFractionDigits();
		if (javaDigits < 0)
		{
			throw new IllegalArgumentException("Currency has no minor units: " + iso);
		}
		return javaDigits;
	}

	private Integer findSapDigits(final String iso)
	{
		try
		{
			final CurrencyModel currency = commonI18NService.getCurrency(iso);
			return currency == null ? null : currency.getDigits();
		}
		catch (final UnknownIdentifierException e)
		{
			return null;
		}
	}

	public void setCommonI18NService(final CommonI18NService commonI18NService)
	{
		this.commonI18NService = commonI18NService;
	}
}
