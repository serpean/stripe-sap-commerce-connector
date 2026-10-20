package com.stripe.core.dao;

import com.stripe.core.model.StripePaymentIntentRecordModel;
import de.hybris.platform.core.model.order.OrderModel;

import java.util.Optional;

/**
 * Finds {@link StripePaymentIntentRecordModel} items.
 */
public interface StripePaymentIntentRecordDao
{
	Optional<StripePaymentIntentRecordModel> findByPaymentIntentId(String paymentIntentId);

	Optional<StripePaymentIntentRecordModel> findByOrder(OrderModel order);

	/**
	 * Counts the records created for a cart code, whatever their state.
	 */
	int countByCartCode(String cartCode);
}
