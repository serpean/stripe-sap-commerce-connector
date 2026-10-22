package com.stripe.core.payment.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.impl.DefaultStripeAmountConverter;
import com.stripe.core.testsupport.StripeLogCapture;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.core.model.order.AbstractOrderEntryModel;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class DefaultStripeLineItemsBuilderTest
{
	private DefaultStripeLineItemsBuilder builder;
	private CartModel cart;
	private List<AbstractOrderEntryModel> entries;

	@Before
	public void setUp()
	{
		final CurrencyModel currency = new CurrencyModel();
		currency.setIsocode("EUR");
		currency.setDigits(2);
		final CommonI18NService i18n = mock(CommonI18NService.class);
		when(i18n.getCurrency("EUR")).thenReturn(currency);
		final DefaultStripeAmountConverter converter = new DefaultStripeAmountConverter();
		converter.setCommonI18NService(i18n);
		builder = new DefaultStripeLineItemsBuilder();
		builder.setStripeAmountConverter(converter);

		cart = new CartModel();
		cart.setCode("c1");
		cart.setCurrency(currency);
		cart.setNet(Boolean.FALSE);
		cart.setDeliveryCost(4.99);
		cart.setTotalTax(0.0);
		entries = new ArrayList<>();
		entries.add(entry("Mug", 10.0, 2, 20.0));
		entries.add(entry("Pen", 5.0, 1, 5.0));
		cart.setEntries(entries);
		cart.setTotalPrice(29.99);
	}

	private AbstractOrderEntryModel entry(final String name, final double base, final long quantity, final double total)
	{
		final ProductModel product = new ProductModel()
		{
			@Override
			public String getName()
			{
				return name;
			}
		};
		product.setCode(name);
		final AbstractOrderEntryModel entry = new AbstractOrderEntryModel();
		entry.setProduct(product);
		entry.setBasePrice(base);
		entry.setQuantity(quantity);
		entry.setTotalPrice(total);
		return entry;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> details(final Map<String, Object> params)
	{
		return (Map<String, Object>) params.get("amount_details");
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> lines(final Map<String, Object> params)
	{
		return (List<Map<String, Object>>) details(params).get("line_items");
	}

	@SuppressWarnings("unchecked")
	private long number(final Map<String, Object> map, final String key, final String inner)
	{
		return ((Number) ((Map<String, Object>) map.get(key)).get(inner)).longValue();
	}

	/** Stripe formula: sum(unit_cost * quantity) + shipping + tax - discount_amount. */
	private long stripeTotal(final Map<String, Object> params)
	{
		final Map<String, Object> details = details(params);
		long total = 0;
		for (final Map<String, Object> line : lines(params))
		{
			assertFalse("line discounts and the order discount are mutually exclusive", line.containsKey("discount_amount"));
			total += ((Number) line.get("unit_cost")).longValue() * ((Number) line.get("quantity")).longValue();
		}
		total += number(details, "shipping", "amount");
		if (details.containsKey("tax"))
		{
			total += number(details, "tax", "total_tax_amount");
		}
		if (details.containsKey("discount_amount"))
		{
			total -= ((Number) details.get("discount_amount")).longValue();
		}
		return total;
	}

	@Test
	public void linesAddUpToAmount()
	{
		final Map<String, Object> params = builder.build(cart, 2999L);

		assertEquals(2, lines(params).size());
		assertEquals("Mug", lines(params).get(0).get("product_name"));
		assertEquals(1000L, ((Number) lines(params).get(0).get("unit_cost")).longValue());
		assertEquals(499L, number(details(params), "shipping", "amount"));
		assertFalse(details(params).containsKey("discount_amount"));
		assertFalse(details(params).containsKey("tax"));
		assertEquals(2999L, stripeTotal(params));
		assertEquals("c1", ((Map<?, ?>) params.get("payment_details")).get("order_reference"));
	}

	@Test
	public void orderDiscountGoesToDiscountAmount()
	{
		cart.setTotalPrice(26.99);

		final Map<String, Object> params = builder.build(cart, 2699L);

		assertEquals(300L, ((Number) details(params).get("discount_amount")).longValue());
		assertEquals(2699L, stripeTotal(params));
	}

	@Test
	public void lineDiscountIsFoldedIntoTheOrderDiscount()
	{
		entries.set(0, entry("Mug", 10.0, 2, 18.0));
		cart.setTotalPrice(27.99);

		final Map<String, Object> params = builder.build(cart, 2799L);

		assertEquals(200L, ((Number) details(params).get("discount_amount")).longValue());
		assertEquals(2799L, stripeTotal(params));
	}

	@Test
	public void netPricesSendTax()
	{
		cart.setNet(Boolean.TRUE);
		cart.setTotalTax(4.2);

		final Map<String, Object> params = builder.build(cart, 3419L);

		assertEquals(420L, number(details(params), "tax", "total_tax_amount"));
		assertEquals(3419L, stripeTotal(params));
	}

	@Test
	public void freeProductLineIsAllowed()
	{
		entries.add(entry("Gift", 0.0, 1, 0.0));

		final Map<String, Object> params = builder.build(cart, 2999L);

		assertFalse(params.isEmpty());
		assertEquals(0L, ((Number) lines(params).get(2).get("unit_cost")).longValue());
		assertEquals(2999L, stripeTotal(params));
	}

	@Test
	public void fullDiscountStillAddsUp()
	{
		cart.setDeliveryCost(0.0);

		final Map<String, Object> params = builder.build(cart, 0L);

		assertFalse(params.isEmpty());
		assertEquals(2500L, ((Number) details(params).get("discount_amount")).longValue());
		assertEquals(0L, stripeTotal(params));
	}

	@Test
	public void discountAboveLinesReturnsEmpty()
	{
		try (StripeLogCapture log = StripeLogCapture.of(DefaultStripeLineItemsBuilder.class))
		{
			final Map<String, Object> params = builder.build(cart, -100L);

			assertTrue(params.isEmpty());
			assertTrue(log.hasWarn("c1"));
		}
	}

	@Test
	public void amountAboveLinesReturnsEmpty()
	{
		try (StripeLogCapture log = StripeLogCapture.of(DefaultStripeLineItemsBuilder.class))
		{
			final Map<String, Object> params = builder.build(cart, 5000L);

			assertTrue(params.isEmpty());
			assertTrue(log.hasWarn("c1"));
		}
	}

	@Test
	public void moreThan200EntriesReturnsEmpty()
	{
		for (int i = 0; i < 200; i++)
		{
			entries.add(entry("P" + i, 0.0, 1, 0.0));
		}

		try (StripeLogCapture log = StripeLogCapture.of(DefaultStripeLineItemsBuilder.class))
		{
			assertTrue(builder.build(cart, 2999L).isEmpty());
			assertTrue(log.hasWarn("c1"));
		}
	}

	@Test
	public void longProductNameIsCut()
	{
		entries.set(0, entry("x".repeat(2000), 10.0, 2, 20.0));

		final Map<String, Object> params = builder.build(cart, 2999L);

		assertEquals(1024, ((String) lines(params).get(0).get("product_name")).length());
	}

	@Test
	public void emptyCartReturnsEmpty()
	{
		entries.clear();

		assertTrue(builder.build(cart, 499L).isEmpty());
	}
}
