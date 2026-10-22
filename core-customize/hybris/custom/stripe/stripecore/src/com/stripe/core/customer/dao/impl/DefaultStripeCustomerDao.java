package com.stripe.core.customer.dao.impl;

import com.stripe.core.customer.dao.StripeCustomerDao;
import com.stripe.core.model.StripeCustomerModel;
import de.hybris.platform.core.model.user.CustomerModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;

import java.util.Optional;

/**
 * FlexibleSearch implementation of {@link StripeCustomerDao}.
 */
public class DefaultStripeCustomerDao implements StripeCustomerDao
{
	private static final String BY_CUSTOMER_AND_ACCOUNT = "SELECT {pk} FROM {StripeCustomer} "
			+ "WHERE {customer} = ?customer AND {accountKey} = ?accountKey";
	private static final String BY_STRIPE_ID = "SELECT {pk} FROM {StripeCustomer} WHERE {stripeCustomerId} = ?stripeCustomerId";

	private FlexibleSearchService flexibleSearchService;

	@Override
	public Optional<StripeCustomerModel> find(final CustomerModel customer, final String accountKey)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(BY_CUSTOMER_AND_ACCOUNT);
		query.addQueryParameter("customer", customer);
		query.addQueryParameter("accountKey", accountKey);
		return flexibleSearchService.<StripeCustomerModel> search(query).getResult().stream().findFirst();
	}

	@Override
	public Optional<StripeCustomerModel> findByStripeCustomerId(final String stripeCustomerId)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(BY_STRIPE_ID);
		query.addQueryParameter("stripeCustomerId", stripeCustomerId);
		return flexibleSearchService.<StripeCustomerModel> search(query).getResult().stream().findFirst();
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}
}
