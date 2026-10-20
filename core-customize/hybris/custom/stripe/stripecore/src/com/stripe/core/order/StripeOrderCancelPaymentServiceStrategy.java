package com.stripe.core.order;

import com.stripe.core.payment.StripePaymentInfos;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.ordercancel.impl.DefaultOrderCancelPaymentServiceStrategy;
import de.hybris.platform.ordercancel.model.OrderCancelRecordEntryModel;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adjusts the Stripe payment of an order after a cancel. For a Stripe order with the ACCEPTED authorization of its
 * PaymentIntent:
 * <ol>
 * <li>the live PaymentIntent is read first, so a Stripe failure stops the cancel before SAP changes the order;</li>
 * <li>SAP recalculates the order totals ({@code super});</li>
 * <li>{@link StripePaymentAdjustmentService#adjust} voids or refunds the money above the order amount.</li>
 * </ol>
 * When the adjustment fails after the recalculation (refund or void not ACCEPTED, or an exception), a retry task is
 * scheduled, so SAP and Stripe do not stay different. Other orders get only the SAP behaviour.
 */
public class StripeOrderCancelPaymentServiceStrategy extends DefaultOrderCancelPaymentServiceStrategy
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeOrderCancelPaymentServiceStrategy.class);

	private StripePaymentAdjustmentService stripePaymentAdjustmentService;

	@Override
	public void recalculateOrderAndModifyPayments(final OrderModel order)
	{
		if (StripePaymentInfos.paymentIntentId(order) == null)
		{
			super.recalculateOrderAndModifyPayments(order);
			return;
		}
		final Optional<PaymentIntent> paymentIntent = stripePaymentAdjustmentService.livePaymentIntent(order);
		super.recalculateOrderAndModifyPayments(order);
		if (paymentIntent.isEmpty())
		{
			LOG.info("Order {} has no accepted Stripe authorization: no Stripe payment to adjust", order.getCode());
			return;
		}

		boolean adjusted;
		try
		{
			adjusted = stripePaymentAdjustmentService.adjust(order, paymentIntent.get());
		}
		catch (final RuntimeException e)
		{
			LOG.warn("Stripe payment adjustment of order {} failed: {}", order.getCode(), e.getMessage());
			adjusted = false;
		}
		if (!adjusted)
		{
			stripePaymentAdjustmentService.scheduleRetry(order);
		}
	}

	/**
	 * SAP uses this form for orders with a generic payment info only. A Stripe order gets the same recalculation and
	 * payment adjustment as in {@link #recalculateOrderAndModifyPayments(OrderModel)}.
	 */
	@Override
	public void recalculateOrderAndModifyPayments(final OrderModel order, final OrderCancelRecordEntryModel cancelEntry)
	{
		if (StripePaymentInfos.paymentIntentId(order) == null)
		{
			super.recalculateOrderAndModifyPayments(order, cancelEntry);
			return;
		}
		recalculateOrderAndModifyPayments(order);
	}

	public void setStripePaymentAdjustmentService(final StripePaymentAdjustmentService stripePaymentAdjustmentService)
	{
		this.stripePaymentAdjustmentService = stripePaymentAdjustmentService;
	}
}
