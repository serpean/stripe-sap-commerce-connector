package com.stripe.core.process;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.stripe.core.order.StripeOrderCancelPaymentServiceAdapter;
import com.stripe.core.order.StripeOrderCancelPaymentServiceStrategy;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.core.Registry;
import de.hybris.platform.ordercancel.impl.DefaultOrderCancelPaymentServiceAdapter;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import de.hybris.platform.servicelayer.ServicelayerBaseTest;
import java.lang.reflect.Field;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.GenericXmlApplicationContext;

/**
 * Loads dummy SAP order process actions in a child of the core context and checks that the process engine gets the
 * Stripe wrappers, also when the action is overridden through an alias. A dummy OMS cancel payment adapter gets the
 * Stripe adapter.
 */
@IntegrationTest
public class StripeProcessActionsPostProcessorIntegrationTest extends ServicelayerBaseTest
{
	private GenericXmlApplicationContext context;

	@Before
	public void setUp()
	{
		context = new GenericXmlApplicationContext();
		context.setParent(Registry.getCoreApplicationContext());
		context.load("classpath:/stripecore/test/stripecore-process-actions-test-spring.xml");
		context.refresh();
	}

	@After
	public void tearDown()
	{
		context.close();
	}

	@Test
	public void checkOrderActionIsWrapped() throws Exception
	{
		final Object action = context.getBean("checkOrderAction");

		assertTrue(action instanceof StripeAwaitPaymentCheckOrderAction);
		assertTrue(delegateOf(action) instanceof DummyAction);
	}

	@Test
	public void aliasedTakePaymentActionIsWrapped() throws Exception
	{
		final Object action = context.getBean("takePaymentAction");

		assertTrue(action instanceof StripeTakePaymentAction);
		assertTrue(delegateOf(action) instanceof DummyAction);
		assertSame(action, context.getBean("customTakePaymentAction"));
		assertNotNull("the wrapper gets its services from the core context",
				((StripeTakePaymentAction) action).getModelService());
	}

	@Test
	public void omsCancelPaymentAdapterIsWrapped() throws Exception
	{
		final Object adapter = context.getBean("OmsOrderCancelPaymentServiceAdapter");

		assertTrue(adapter instanceof StripeOrderCancelPaymentServiceAdapter);
		assertTrue(delegateOf(adapter) instanceof DefaultOrderCancelPaymentServiceAdapter);
		assertTrue("Stripe orders reach the Stripe cancel strategy",
				((StripeOrderCancelPaymentServiceAdapter) adapter).getOrderCancelPaymentServiceStrategy()
						instanceof StripeOrderCancelPaymentServiceStrategy);
	}

	private static Object delegateOf(final Object wrapper) throws ReflectiveOperationException
	{
		final Field delegate = wrapper.getClass().getDeclaredField("delegate");
		delegate.setAccessible(true);
		return delegate.get(wrapper);
	}

	/**
	 * Stands for an SAP order process action.
	 */
	public static class DummyAction extends AbstractSimpleDecisionAction<OrderProcessModel>
	{
		@Override
		public Transition executeAction(final OrderProcessModel process)
		{
			return Transition.OK;
		}
	}
}
