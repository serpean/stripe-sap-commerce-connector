package com.stripe.core.dao;

import com.stripe.core.model.StripeWebhookEventModel;

import java.util.Optional;

/**
 * Finds {@link StripeWebhookEventModel} items.
 */
public interface StripeWebhookEventDao
{
	Optional<StripeWebhookEventModel> findByEventId(String eventId);
}
