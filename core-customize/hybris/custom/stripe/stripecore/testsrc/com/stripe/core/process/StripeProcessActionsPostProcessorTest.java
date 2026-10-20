package com.stripe.core.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.order.StripeOrderCancelPaymentServiceAdapter;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.ordercancel.OrderCancelPaymentServiceAdapter;
import de.hybris.platform.ordercancel.model.OrderCancelRecordEntryModel;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction.Transition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactory;

@UnitTest
public class StripeProcessActionsPostProcessorTest
{
	private BeanFactory beanFactory;
	private StripeProcessActionsPostProcessor postProcessor;
	private OrderModel stripeOrder;
	private OrderModel otherOrder;
	private OrderProcessModel stripeProcess;
	private OrderProcessModel otherProcess;

	@Before
	public void setUp()
	{
		beanFactory = mock(BeanFactory.class);
		postProcessor = new StripeProcessActionsPostProcessor();
		postProcessor.setBeanFactory(beanFactory);

		final StripePaymentInfoModel paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId("pi_proxy");
		stripeOrder = new OrderModel();
		stripeOrder.setPaymentInfo(paymentInfo);
		otherOrder = new OrderModel();
		stripeProcess = new OrderProcessModel();
		stripeProcess.setOrder(stripeOrder);
		otherProcess = new OrderProcessModel();
		otherProcess.setOrder(otherOrder);
	}

	@Test
	public void proxiedCheckOrderActionKeepsItsType() throws Exception
	{
		final StandInAction checkOrder = new StandInAction();
		final StripeAwaitPaymentCheckOrderAction stripeAction = mock(StripeAwaitPaymentCheckOrderAction.class);
		when(stripeAction.executeAction(any())).thenReturn(Transition.NOK);
		when(beanFactory.getBean("stripeAwaitPaymentCheckOrderAction", checkOrder)).thenReturn(stripeAction);

		final Object result = postProcessor.postProcessAfterInitialization(checkOrder, "checkOrderAction");

		assertTrue(result instanceof StandInAction);
		assertTrue(AopUtils.isCglibProxy(result));
		final StandInAction proxy = (StandInAction) result;
		assertEquals("Stripe orders run the Stripe step", "NOK", proxy.execute(stripeProcess));
		verify(stripeAction).executeAction(stripeProcess);
		assertEquals("other orders run the SAP action", "OK", proxy.execute(otherProcess));
		verify(stripeAction, never()).executeAction(otherProcess);
		assertEquals(List.of(otherProcess), checkOrder.executed);
	}

	@Test
	public void proxiedTakePaymentActionKeepsItsType() throws Exception
	{
		final StandInAction custom = new StandInAction();
		final StripeTakePaymentAction stripeAction = mock(StripeTakePaymentAction.class);
		when(stripeAction.executeAction(any())).thenReturn(Transition.NOK);
		when(beanFactory.getAliases("customTakePaymentAction")).thenReturn(new String[] { "takePaymentAction" });
		when(beanFactory.getBean("stripeTakePaymentAction", custom)).thenReturn(stripeAction);

		final Object result = postProcessor.postProcessAfterInitialization(custom, "customTakePaymentAction");

		assertTrue(result instanceof StandInAction);
		final StandInAction proxy = (StandInAction) result;
		assertEquals("NOK", proxy.execute(stripeProcess));
		assertEquals(Transition.NOK, proxy.executeAction(stripeProcess));
		verify(stripeAction, times(2)).executeAction(stripeProcess);
		assertEquals("OK", proxy.execute(otherProcess));
		assertEquals(List.of(otherProcess), custom.executed);
	}

	@Test
	public void proxiedCancelPaymentAdapterKeepsItsType()
	{
		final StandInCancelPaymentAdapter omsAdapter = new StandInCancelPaymentAdapter();
		final StripeOrderCancelPaymentServiceAdapter stripeAdapter = mock(StripeOrderCancelPaymentServiceAdapter.class);
		when(beanFactory.getBean("stripeOrderCancelPaymentServiceAdapter", omsAdapter)).thenReturn(stripeAdapter);
		final OrderCancelRecordEntryModel cancelEntry = new OrderCancelRecordEntryModel();

		final Object result = postProcessor.postProcessAfterInitialization(omsAdapter,
				"OmsOrderCancelPaymentServiceAdapter");

		assertTrue(result instanceof StandInCancelPaymentAdapter);
		final StandInCancelPaymentAdapter proxy = (StandInCancelPaymentAdapter) result;
		proxy.recalculateOrderAndModifyPayments(stripeOrder);
		proxy.recalculateOrderAndModifyPayments(stripeOrder, cancelEntry);
		verify(stripeAdapter).recalculateOrderAndModifyPayments(stripeOrder);
		verify(stripeAdapter).recalculateOrderAndModifyPayments(stripeOrder, cancelEntry);
		assertTrue(omsAdapter.recalculated.isEmpty());

		proxy.recalculateOrderAndModifyPayments(otherOrder);
		proxy.recalculateOrderAndModifyPayments(otherOrder, cancelEntry);
		assertEquals(List.of(otherOrder, otherOrder), omsAdapter.recalculated);
		verify(stripeAdapter, never()).recalculateOrderAndModifyPayments(otherOrder);
	}

	@Test
	public void otherBeansAreNotChanged()
	{
		final StandInAction other = new StandInAction();

		assertSame(other, postProcessor.postProcessAfterInitialization(other, "fraudCheckOrderAction"));
		verify(beanFactory, never()).getBean(anyString(), (Object) any());
	}

	@Test
	public void proxyIsNotProxiedTwice()
	{
		final StandInAction checkOrder = new StandInAction();
		when(beanFactory.getBean("stripeAwaitPaymentCheckOrderAction", checkOrder))
				.thenReturn(mock(StripeAwaitPaymentCheckOrderAction.class));
		final Object proxy = postProcessor.postProcessAfterInitialization(checkOrder, "checkOrderAction");

		assertSame(proxy, postProcessor.postProcessAfterInitialization(proxy, "checkOrderAction"));
		verify(beanFactory, times(1)).getBean(anyString(), (Object) any());
	}

	@Test
	public void stripeBeanIsNotChanged()
	{
		@SuppressWarnings("unchecked")
		final StripeTakePaymentAction stripeAction = new StripeTakePaymentAction(mock(AbstractSimpleDecisionAction.class));

		assertSame(stripeAction, postProcessor.postProcessAfterInitialization(stripeAction, "takePaymentAction"));
		verify(beanFactory, never()).getBean(anyString(), (Object) any());
	}

	@Test
	public void finalClassIsReplacedByStripeBean()
	{
		final FinalStandInAction checkOrder = new FinalStandInAction();
		final StripeAwaitPaymentCheckOrderAction stripeAction = new StripeAwaitPaymentCheckOrderAction(checkOrder);
		when(beanFactory.getBean("stripeAwaitPaymentCheckOrderAction", checkOrder)).thenReturn(stripeAction);

		assertSame(stripeAction, postProcessor.postProcessAfterInitialization(checkOrder, "checkOrderAction"));
	}

	@Test
	public void beanOfOtherTypeIsKept()
	{
		final Object bean = new Object();

		assertSame(bean, postProcessor.postProcessAfterInitialization(bean, "checkOrderAction"));
		assertSame(bean, postProcessor.postProcessAfterInitialization(bean, "OmsOrderCancelPaymentServiceAdapter"));
		verify(beanFactory, never()).getBean(anyString(), (Object) any());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void statusDisplayMapGetsPaymentPending()
	{
		final Map<String, String> displayMap = new LinkedHashMap<>(Map.of("CREATED", "created"));

		final Map<String, String> result = (Map<String, String>) postProcessor.postProcessAfterInitialization(displayMap,
				"acceleratorStatusDisplayMap");

		assertEquals("paymentPending", result.get("PAYMENT_PENDING"));
		assertEquals("created", result.get("CREATED"));
	}

	@Test
	public void aliasedStatusDisplayMapGetsPaymentPending()
	{
		when(beanFactory.getAliases("customStatusDisplayMap")).thenReturn(new String[] { "acceleratorStatusDisplayMap" });

		final Object displayMap = postProcessor.postProcessAfterInitialization(new LinkedHashMap<>(),
				"customStatusDisplayMap");

		assertEquals("paymentPending", ((Map<?, ?>) displayMap).get("PAYMENT_PENDING"));
	}

	/**
	 * Stands for an SAP order process action.
	 */
	public static class StandInAction extends AbstractSimpleDecisionAction<OrderProcessModel>
	{
		final List<OrderProcessModel> executed = new ArrayList<>();

		@Override
		public Transition executeAction(final OrderProcessModel process)
		{
			executed.add(process);
			return Transition.OK;
		}
	}

	/**
	 * Stands for an SAP order process action that cannot be proxied.
	 */
	public static final class FinalStandInAction extends AbstractSimpleDecisionAction<OrderProcessModel>
	{
		@Override
		public Transition executeAction(final OrderProcessModel process)
		{
			return Transition.OK;
		}
	}

	/**
	 * Stands for the OMS cancel payment adapter of warehousing.
	 */
	public static class StandInCancelPaymentAdapter implements OrderCancelPaymentServiceAdapter
	{
		final List<OrderModel> recalculated = new ArrayList<>();

		@Override
		public void recalculateOrderAndModifyPayments(final OrderModel order)
		{
			recalculated.add(order);
		}

		@Override
		public void recalculateOrderAndModifyPayments(final OrderModel order, final OrderCancelRecordEntryModel cancelEntry)
		{
			recalculated.add(order);
		}
	}
}
