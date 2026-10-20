package com.stripe.core.dao.impl;

import com.stripe.core.dao.StripeWebhookEventDao;
import com.stripe.core.model.StripeWebhookEventModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;

import java.util.Collections;
import java.util.Optional;

/**
 * FlexibleSearch implementation of {@link StripeWebhookEventDao}.
 */
public class DefaultStripeWebhookEventDao implements StripeWebhookEventDao
{
	private static final String BY_EVENT_ID = "SELECT {pk} FROM {StripeWebhookEvent} WHERE {eventId} = ?eventId";
	private static final String TASKS_OF_EVENT = "SELECT COUNT({pk}) FROM {Task} WHERE {contextItem} = ?event";

	private FlexibleSearchService flexibleSearchService;

	@Override
	public Optional<StripeWebhookEventModel> findByEventId(final String eventId)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(BY_EVENT_ID);
		query.addQueryParameter("eventId", eventId);
		return flexibleSearchService.<StripeWebhookEventModel> search(query).getResult().stream().findFirst();
	}

	@Override
	public boolean hasScheduledTask(final StripeWebhookEventModel event)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(TASKS_OF_EVENT);
		query.addQueryParameter("event", event.getPk());
		query.setResultClassList(Collections.singletonList(Integer.class));
		final Integer count = flexibleSearchService.<Integer> search(query).getResult().stream().findFirst().orElse(0);
		return count > 0;
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}
}
