package com.stripe.facades.order.impl;

import de.hybris.platform.core.model.order.AbstractOrderEntryModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.ordermanagementfacades.cancellation.data.OrderCancelEntryData;
import de.hybris.platform.ordermanagementfacades.cancellation.data.OrderCancelRequestData;
import de.hybris.platform.ordermanagementfacades.order.impl.DefaultOmsOrderFacade;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The OMS order facade with a right "partial cancel" test. The SAP test compares the entry numbers of the order (a list
 * of Integer) with the entries of the request (a list of OrderCancelEntryData) with {@code containsAll}, which is never
 * true, so every request counted as a partial cancel. The cancel denial strategies then denied the full cancel of an
 * order whose Stripe payment is pending. Here a cancel is partial when the request does not cover every entry of the
 * order. The quantities are checked by {@code isPartialEntryCancel}, which is not changed.
 */
public class StripeOmsOrderFacade extends DefaultOmsOrderFacade
{
	@Override
	public Boolean isPartialCancel(final OrderCancelRequestData orderCancelRequestData, final OrderModel order)
	{
		if (orderCancelRequestData.getEntries() == null || order.getEntries() == null)
		{
			// Nothing to compare: a request without entries covers no entry, so it is partial (SAP validation rejects it).
			return Boolean.TRUE;
		}
		final Set<Integer> requested = orderCancelRequestData.getEntries().stream().map(OrderCancelEntryData::getOrderEntryNumber)
				.collect(Collectors.toSet());
		return !requested.containsAll(order.getEntries().stream().map(AbstractOrderEntryModel::getEntryNumber)
				.collect(Collectors.toSet()));
	}
}
