package com.stripe.core.customer.dao;

import com.stripe.core.model.StripeCustomerModel;
import de.hybris.platform.core.model.user.CustomerModel;

import java.util.Optional;

/**
 * Finds {@link StripeCustomerModel} items.
 */
public interface StripeCustomerDao
{
	Optional<StripeCustomerModel> find(CustomerModel customer, String accountKey);

	Optional<StripeCustomerModel> findByStripeCustomerId(String stripeCustomerId);
}
