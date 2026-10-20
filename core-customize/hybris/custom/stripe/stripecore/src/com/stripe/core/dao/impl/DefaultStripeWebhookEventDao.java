package com.stripe.core.dao.impl;

import com.stripe.core.dao.StripeWebhookEventDao;
import com.stripe.core.model.StripeWebhookEventModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;

import java.util.Optional;

/**
 * FlexibleSearch implementation of {@link StripeWebhookEventDao}.
 */
public class DefaultStripeWebhookEventDao implements StripeWebhookEventDao
{
	private static final String BY_EVENT_ID = "SELECT {pk} FROM {StripeWebhookEvent} WHERE {eventId} = ?eventId";

	private FlexibleSearchService flexibleSearchService;

	@Override
	public Optional<StripeWebhookEventModel> findByEventId(final String eventId)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(BY_EVENT_ID);
		query.addQueryParameter("eventId", eventId);
		return flexibleSearchService.<StripeWebhookEventModel> search(query).getResult().stream().findFirst();
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}
}
