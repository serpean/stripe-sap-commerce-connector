package com.stripe.core.dao.impl;

import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;

import java.util.Optional;

/**
 * FlexibleSearch implementation of {@link StripePaymentIntentRecordDao}.
 */
public class DefaultStripePaymentIntentRecordDao implements StripePaymentIntentRecordDao
{
	private static final String BY_PAYMENT_INTENT_ID =
			"SELECT {pk} FROM {StripePaymentIntentRecord} WHERE {paymentIntentId} = ?paymentIntentId";
	private static final String BY_CART_CODE = "SELECT {pk} FROM {StripePaymentIntentRecord} WHERE {cartCode} = ?cartCode";
	private static final String BY_ORDER = "SELECT {pk} FROM {StripePaymentIntentRecord} WHERE {order} = ?order";

	private FlexibleSearchService flexibleSearchService;

	@Override
	public Optional<StripePaymentIntentRecordModel> findByPaymentIntentId(final String paymentIntentId)
	{
		return findOne(BY_PAYMENT_INTENT_ID, "paymentIntentId", paymentIntentId);
	}

	@Override
	public Optional<StripePaymentIntentRecordModel> findByOrder(final OrderModel order)
	{
		return findOne(BY_ORDER, "order", order);
	}

	@Override
	public int countByCartCode(final String cartCode)
	{
		final FlexibleSearchQuery searchQuery = new FlexibleSearchQuery(BY_CART_CODE);
		searchQuery.addQueryParameter("cartCode", cartCode);
		return flexibleSearchService.search(searchQuery).getTotalCount();
	}

	private Optional<StripePaymentIntentRecordModel> findOne(final String query, final String name, final Object value)
	{
		final FlexibleSearchQuery searchQuery = new FlexibleSearchQuery(query);
		searchQuery.addQueryParameter(name, value);
		return flexibleSearchService.<StripePaymentIntentRecordModel> search(searchQuery).getResult().stream().findFirst();
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}
}
