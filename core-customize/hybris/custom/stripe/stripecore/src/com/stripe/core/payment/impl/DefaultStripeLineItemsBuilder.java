package com.stripe.core.payment.impl;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.payment.StripeLineItemsBuilder;
import de.hybris.platform.core.model.order.AbstractOrderEntryModel;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripeLineItemsBuilder}. Stripe does not accept line discounts and an order discount together, so
 * every unit cost is the gross base price and the whole discount (line discounts, order discounts and rounding) goes
 * to {@code amount_details.discount_amount}.
 */
public class DefaultStripeLineItemsBuilder implements StripeLineItemsBuilder
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeLineItemsBuilder.class);

	private static final int MAX_ENTRIES = 200;
	private static final int MAX_NAME_LENGTH = 1024;

	private StripeAmountConverter stripeAmountConverter;

	@Override
	public Map<String, Object> build(final AbstractOrderModel order, final long amountMinor)
	{
		try
		{
			return buildParams(order, amountMinor);
		}
		catch (final RuntimeException e)
		{
			return skip(order, "error " + e.getClass().getSimpleName());
		}
	}

	protected Map<String, Object> buildParams(final AbstractOrderModel order, final long amountMinor)
	{
		final List<AbstractOrderEntryModel> entries = order.getEntries();
		if (entries == null || entries.isEmpty() || entries.size() > MAX_ENTRIES)
		{
			return skip(order, "it has " + (entries == null ? 0 : entries.size()) + " entries");
		}
		final String currencyIso = order.getCurrency().getIsocode();

		final List<Map<String, Object>> lines = new ArrayList<>();
		long gross = 0L;
		for (final AbstractOrderEntryModel entry : entries)
		{
			if (entry.getBasePrice() == null || entry.getQuantity() == null || entry.getQuantity() <= 0)
			{
				return skip(order, "an entry has no price or quantity");
			}
			final long unitCost = stripeAmountConverter.toMinorUnits(BigDecimal.valueOf(entry.getBasePrice()), currencyIso);
			final Map<String, Object> line = new LinkedHashMap<>();
			line.put("product_name", productName(entry));
			line.put("quantity", entry.getQuantity());
			line.put("unit_cost", unitCost);
			lines.add(line);
			gross += unitCost * entry.getQuantity();
		}

		final long shipping = order.getDeliveryCost() == null ? 0L
				: stripeAmountConverter.toMinorUnits(BigDecimal.valueOf(order.getDeliveryCost()), currencyIso);
		final boolean net = Boolean.TRUE.equals(order.getNet());
		final long tax = net && order.getTotalTax() != null
				? stripeAmountConverter.toMinorUnits(BigDecimal.valueOf(order.getTotalTax()), currencyIso) : 0L;
		final long discount = gross + shipping + tax - amountMinor;
		if (shipping < 0 || discount < 0 || discount > gross)
		{
			return skip(order, "lines " + gross + ", shipping " + shipping + ", tax " + tax + " do not fit amount " + amountMinor);
		}

		final Map<String, Object> amountDetails = new LinkedHashMap<>();
		amountDetails.put("line_items", lines);
		amountDetails.put("shipping", Map.of("amount", shipping));
		if (net)
		{
			amountDetails.put("tax", Map.of("total_tax_amount", tax));
		}
		if (discount > 0)
		{
			amountDetails.put("discount_amount", discount);
		}
		final Map<String, Object> params = new LinkedHashMap<>();
		params.put("amount_details", amountDetails);
		params.put("payment_details", Map.of("order_reference", order.getCode()));
		return params;
	}

	protected String productName(final AbstractOrderEntryModel entry)
	{
		String name = entry.getProduct() == null ? null : entry.getProduct().getName();
		if (StringUtils.isBlank(name))
		{
			name = entry.getProduct() == null ? "-" : entry.getProduct().getCode();
		}
		return StringUtils.defaultIfBlank(StringUtils.left(name, MAX_NAME_LENGTH), "-");
	}

	private Map<String, Object> skip(final AbstractOrderModel order, final String reason)
	{
		LOG.warn("Line items of {} are not sent to Stripe: {}", order.getCode(), reason);
		return Map.of();
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}
}
