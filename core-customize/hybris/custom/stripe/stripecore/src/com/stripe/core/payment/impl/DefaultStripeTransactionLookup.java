package com.stripe.core.payment.impl;

import com.stripe.core.payment.StripeTransactionLookup;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;

import java.util.Objects;
import java.util.Optional;

/**
 * FlexibleSearch implementation of {@link StripeTransactionLookup}.
 */
public class DefaultStripeTransactionLookup implements StripeTransactionLookup
{
	private static final String AUTHORIZATION_BY_REQUEST_ID = "SELECT {pk} FROM {PaymentTransactionEntry}"
			+ " WHERE {requestId} = ?requestId AND {type} = ?type";

	private FlexibleSearchService flexibleSearchService;

	@Override
	public Optional<PaymentTransactionModel> findByPaymentIntentId(final String paymentIntentId)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(AUTHORIZATION_BY_REQUEST_ID);
		query.addQueryParameter("requestId", paymentIntentId);
		query.addQueryParameter("type", PaymentTransactionType.AUTHORIZATION);
		return flexibleSearchService.<PaymentTransactionEntryModel> search(query).getResult().stream()
				.map(PaymentTransactionEntryModel::getPaymentTransaction)
				.filter(Objects::nonNull)
				.findFirst();
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}
}
