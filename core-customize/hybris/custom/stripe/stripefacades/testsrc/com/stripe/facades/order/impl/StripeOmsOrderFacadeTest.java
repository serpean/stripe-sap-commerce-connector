package com.stripe.facades.order.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.order.OrderEntryModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.ordermanagementfacades.cancellation.data.OrderCancelEntryData;
import de.hybris.platform.ordermanagementfacades.cancellation.data.OrderCancelRequestData;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

@UnitTest
public class StripeOmsOrderFacadeTest
{
	private final StripeOmsOrderFacade facade = new StripeOmsOrderFacade();

	@Test
	public void requestThatCoversEveryEntryIsNotPartial()
	{
		assertFalse(facade.isPartialCancel(request(0, 1), order(0, 1)));
	}

	@Test
	public void requestInAnotherOrderIsStillNotPartial()
	{
		assertFalse(facade.isPartialCancel(request(1, 0), order(0, 1)));
	}

	@Test
	public void requestWithAMissingEntryIsPartial()
	{
		assertTrue(facade.isPartialCancel(request(0), order(0, 1)));
	}

	@Test
	public void requestForOneOfOneEntryIsNotPartial()
	{
		assertFalse(facade.isPartialCancel(request(0), order(0)));
	}

	@Test
	public void requestWithoutEntriesIsPartialAndDoesNotFail()
	{
		assertTrue(facade.isPartialCancel(new OrderCancelRequestData(), order(0)));
	}

	private static OrderCancelRequestData request(final int... entryNumbers)
	{
		final List<OrderCancelEntryData> entries = new ArrayList<>();
		for (final int number : entryNumbers)
		{
			final OrderCancelEntryData entry = new OrderCancelEntryData();
			entry.setOrderEntryNumber(number);
			entries.add(entry);
		}
		final OrderCancelRequestData request = new OrderCancelRequestData();
		request.setEntries(entries);
		return request;
	}

	private static OrderModel order(final int... entryNumbers)
	{
		final List<de.hybris.platform.core.model.order.AbstractOrderEntryModel> entries = new ArrayList<>();
		for (final int number : entryNumbers)
		{
			final OrderEntryModel entry = mock(OrderEntryModel.class);
			when(entry.getEntryNumber()).thenReturn(number);
			entries.add(entry);
		}
		final OrderModel order = mock(OrderModel.class);
		when(order.getEntries()).thenReturn(entries);
		return order;
	}
}
