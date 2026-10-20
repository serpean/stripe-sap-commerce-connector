package com.stripe.core.order;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.stripe.core.model.StripePaymentInfoModel;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.order.payment.CreditCardPaymentInfoModel;
import de.hybris.platform.ordercancel.OrderCancelPaymentServiceAdapter;
import de.hybris.platform.ordercancel.OrderCancelPaymentServiceStrategy;
import de.hybris.platform.ordercancel.model.OrderCancelRecordEntryModel;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripeOrderCancelPaymentServiceAdapterTest
{
	private OrderCancelPaymentServiceAdapter delegate;
	private OrderCancelPaymentServiceStrategy strategy;
	private StripeOrderCancelPaymentServiceAdapter adapter;
	private OrderModel order;

	@Before
	public void setUp()
	{
		delegate = mock(OrderCancelPaymentServiceAdapter.class);
		strategy = mock(OrderCancelPaymentServiceStrategy.class);
		adapter = new StripeOrderCancelPaymentServiceAdapter(delegate);
		adapter.setOrderCancelPaymentServiceStrategy(strategy);
		order = new OrderModel();
	}

	@Test
	public void omsAdapterDelegatesForNonStripeOrders()
	{
		order.setPaymentInfo(new CreditCardPaymentInfoModel());
		final OrderCancelRecordEntryModel cancelEntry = new OrderCancelRecordEntryModel();

		adapter.recalculateOrderAndModifyPayments(order);
		adapter.recalculateOrderAndModifyPayments(order, cancelEntry);

		verify(delegate).recalculateOrderAndModifyPayments(order);
		verify(delegate).recalculateOrderAndModifyPayments(order, cancelEntry);
		verifyNoInteractions(strategy);
	}

	@Test
	public void stripeOrdersGoToTheStrategy()
	{
		final StripePaymentInfoModel paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId("pi_oms");
		order.setPaymentInfo(paymentInfo);
		final OrderCancelRecordEntryModel cancelEntry = new OrderCancelRecordEntryModel();

		adapter.recalculateOrderAndModifyPayments(order);
		adapter.recalculateOrderAndModifyPayments(order, cancelEntry);

		verify(strategy).recalculateOrderAndModifyPayments(order);
		verify(strategy).recalculateOrderAndModifyPayments(order, cancelEntry);
		verifyNoInteractions(delegate);
	}
}
