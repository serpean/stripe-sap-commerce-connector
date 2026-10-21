package com.stripe.core.order;

import com.stripe.core.payment.StripePaymentInfos;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.ordercancel.OrderCancelPaymentServiceAdapter;
import de.hybris.platform.ordercancel.OrderCancelPaymentServiceStrategy;
import de.hybris.platform.ordercancel.model.OrderCancelRecordEntryModel;

/**
 * Wraps a cancel payment adapter that does not reach the cancel payment strategy (the OMS adapter of warehousing only
 * logs). Stripe orders go to the strategy, so the order is recalculated and its Stripe payment adjusted; other orders go
 * to the wrapped adapter.
 */
public class StripeOrderCancelPaymentServiceAdapter implements OrderCancelPaymentServiceAdapter
{
	private final OrderCancelPaymentServiceAdapter delegate;
	private OrderCancelPaymentServiceStrategy orderCancelPaymentServiceStrategy;

	public StripeOrderCancelPaymentServiceAdapter(final OrderCancelPaymentServiceAdapter delegate)
	{
		this.delegate = delegate;
	}

	@Override
	public void recalculateOrderAndModifyPayments(final OrderModel order)
	{
		if (StripePaymentInfos.paymentIntentId(order) == null)
		{
			delegate.recalculateOrderAndModifyPayments(order);
			return;
		}
		orderCancelPaymentServiceStrategy.recalculateOrderAndModifyPayments(order);
	}

	@Override
	public void recalculateOrderAndModifyPayments(final OrderModel order, final OrderCancelRecordEntryModel cancelEntry)
	{
		if (StripePaymentInfos.paymentIntentId(order) == null)
		{
			delegate.recalculateOrderAndModifyPayments(order, cancelEntry);
			return;
		}
		orderCancelPaymentServiceStrategy.recalculateOrderAndModifyPayments(order, cancelEntry);
	}

	public OrderCancelPaymentServiceStrategy getOrderCancelPaymentServiceStrategy()
	{
		return orderCancelPaymentServiceStrategy;
	}

	public void setOrderCancelPaymentServiceStrategy(
			final OrderCancelPaymentServiceStrategy orderCancelPaymentServiceStrategy)
	{
		this.orderCancelPaymentServiceStrategy = orderCancelPaymentServiceStrategy;
	}
}
