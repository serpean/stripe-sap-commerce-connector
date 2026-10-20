package com.stripe.core.amount.impl;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.servicelayer.exceptions.UnknownIdentifierException;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class DefaultStripeAmountConverterTest
{
	private CommonI18NService commonI18NService;
	private DefaultStripeAmountConverter converter;

	@Before
	public void setUp()
	{
		commonI18NService = mock(CommonI18NService.class);
		converter = new DefaultStripeAmountConverter();
		converter.setCommonI18NService(commonI18NService);
	}

	private void sapCurrency(final String iso, final Integer digits)
	{
		final CurrencyModel currency = mock(CurrencyModel.class);
		when(currency.getDigits()).thenReturn(digits);
		when(commonI18NService.getCurrency(iso)).thenReturn(currency);
	}

	@Test
	public void jpyHasNoMinorUnits()
	{
		sapCurrency("JPY", Integer.valueOf(0));
		assertEquals(1000L, converter.toMinorUnits(new BigDecimal("1000"), "JPY"));
	}

	@Test
	public void eurHasTwoDecimals()
	{
		sapCurrency("EUR", Integer.valueOf(2));
		assertEquals(33954L, converter.toMinorUnits(new BigDecimal("339.54"), "EUR"));
		assertEquals(0, new BigDecimal("339.54").compareTo(converter.fromMinorUnits(33954L, "EUR")));
	}

	@Test
	public void roundsHalfUp()
	{
		sapCurrency("USD", Integer.valueOf(2));
		assertEquals(1001L, converter.toMinorUnits(new BigDecimal("10.005"), "USD"));
	}

	@Test
	public void sapDigitsWinOverJavaCurrency()
	{
		sapCurrency("EUR", Integer.valueOf(3));
		assertEquals(1234L, converter.toMinorUnits(new BigDecimal("1.234"), "EUR"));
	}

	@Test
	public void fallsBackToJavaCurrencyWhenSapHasNoDigits()
	{
		sapCurrency("JPY", null);
		assertEquals(1000L, converter.toMinorUnits(new BigDecimal("1000"), "JPY"));
	}

	@Test
	public void fallsBackToJavaCurrencyWhenSapHasNoCurrency()
	{
		when(commonI18NService.getCurrency("USD")).thenThrow(new UnknownIdentifierException("none"));
		assertEquals(1234L, converter.toMinorUnits(new BigDecimal("12.34"), "USD"));
		assertEquals(0, new BigDecimal("12.34").compareTo(converter.fromMinorUnits(1234L, "USD")));
	}

	@Test(expected = IllegalArgumentException.class)
	public void unknownCurrencyFails()
	{
		when(commonI18NService.getCurrency("XXQ")).thenThrow(new UnknownIdentifierException("none"));
		converter.toMinorUnits(BigDecimal.ONE, "XXQ");
	}
}
