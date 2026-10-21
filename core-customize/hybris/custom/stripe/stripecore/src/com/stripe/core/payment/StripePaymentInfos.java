package com.stripe.core.payment;

import com.stripe.core.model.StripePaymentInfoModel;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import org.apache.commons.lang3.StringUtils;

/**
 * Reads the Stripe payment of a cart or an order.
 */
public final class StripePaymentInfos
{
	private StripePaymentInfos()
	{
	}

	/**
	 * @return the PaymentIntent id of the Stripe payment info of the order, or null when the order is not paid with Stripe
	 */
	public static String paymentIntentId(final AbstractOrderModel order)
	{
		if (order != null && order.getPaymentInfo() instanceof StripePaymentInfoModel paymentInfo)
		{
			return StringUtils.trimToNull(paymentInfo.getStripePaymentIntentId());
		}
		return null;
	}
}
