package com.stripe.core.order;

import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.payment.StripePaymentInfos;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.security.PrincipalModel;
import de.hybris.platform.ordercancel.DefaultOrderCancelDenialReason;
import de.hybris.platform.ordercancel.OrderCancelDenialReason;
import de.hybris.platform.ordercancel.OrderCancelDenialStrategy;
import de.hybris.platform.ordercancel.model.OrderCancelConfigModel;
import java.util.Set;

/**
 * Denies the partial cancel of a Stripe order while its payment is pending (record state PROCESSING or
 * AWAITING_FUNDS). The money has not arrived, so the amount to adjust is not known. A full cancel is not denied: it
 * cancels the PaymentIntent. Orders not paid with Stripe are not affected.
 */
public class StripePendingPaymentCancelDenialStrategy implements OrderCancelDenialStrategy
{
	/**
	 * The code of the denial reason. The SAP reason code is an integer, so the stable name
	 * {@code stripe-payment-pending} is the start of the description.
	 */
	public static final int REASON_CODE = 7301;
	public static final String REASON_NAME = "stripe-payment-pending";

	private static final Set<StripePaymentIntentState> PENDING = Set.of(StripePaymentIntentState.PROCESSING,
			StripePaymentIntentState.AWAITING_FUNDS);

	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;

	@Override
	public OrderCancelDenialReason getCancelDenialReason(final OrderCancelConfigModel configuration,
			final OrderModel order, final PrincipalModel requestor, final boolean partialCancel,
			final boolean partialEntryCancel)
	{
		if (!partialCancel && !partialEntryCancel)
		{
			return null;
		}
		final String paymentIntentId = StripePaymentInfos.paymentIntentId(order);
		if (paymentIntentId == null)
		{
			return null;
		}
		final boolean pending = stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntentId)
				.map(record -> PENDING.contains(record.getState())).orElse(false);
		return pending ? new DefaultOrderCancelDenialReason(REASON_CODE,
				REASON_NAME + ": the order cannot be partially cancelled while its Stripe payment is pending") : null;
	}

	public void setStripePaymentIntentRecordDao(final StripePaymentIntentRecordDao stripePaymentIntentRecordDao)
	{
		this.stripePaymentIntentRecordDao = stripePaymentIntentRecordDao;
	}
}
