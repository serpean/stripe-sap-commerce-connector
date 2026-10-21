package com.stripe.core.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.order.StripeOrderCancelPaymentServiceAdapter;
import com.stripe.core.order.StripeOrderCancelPaymentServiceStrategy;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.core.Registry;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.ordercancel.impl.DefaultOrderCancelPaymentServiceAdapter;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import de.hybris.platform.servicelayer.ServicelayerTransactionalTest;
import de.hybris.platform.servicelayer.i18n.CommonI18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.user.UserService;
import de.hybris.platform.task.RetryLaterException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import javax.annotation.Resource;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericXmlApplicationContext;

/**
 * Loads dummy SAP order process actions in a child of the core context and checks that the process engine gets class
 * proxies of them that send Stripe orders to the Stripe steps, also when the action is overridden through an alias. A
 * dummy OMS cancel payment adapter gets the Stripe adapter. When order management is installed, its real beans are
 * checked too.
 */
@IntegrationTest
public class StripeProcessActionsPostProcessorIntegrationTest extends ServicelayerTransactionalTest
{
	@Resource
	private ModelService modelService;
	@Resource
	private UserService userService;
	@Resource
	private CommonI18NService commonI18NService;

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
	public void proxiedCheckOrderActionKeepsItsType() throws Exception
	{
		final Object bean = context.getBean("checkOrderAction");

		assertTrue(bean instanceof DummyAction);
		assertTrue(AopUtils.isCglibProxy(bean));
		final Object stripeAction = stripeTargetOf(bean);
		assertTrue(stripeAction instanceof StripeAwaitPaymentCheckOrderAction);
		assertTrue(delegateOf(stripeAction) instanceof DummyAction);

		final DummyAction action = (DummyAction) bean;
		assertEquals("other orders run the SAP action", "OK", action.execute(process(order("proxy-check-1", null))));
		final OrderModel stripeOrder = order("proxy-check-2", "pi_proxy_check");
		try
		{
			action.execute(process(stripeOrder));
			fail("a Stripe order with a pending payment waits");
		}
		catch (final RetryLaterException expected)
		{
			assertEquals(OrderStatus.PAYMENT_PENDING, stripeOrder.getStatus());
		}
	}

	@Test
	public void proxiedTakePaymentActionKeepsItsType() throws Exception
	{
		final Object bean = context.getBean("takePaymentAction");

		assertTrue(bean instanceof DummyAction);
		assertSame(bean, context.getBean("customTakePaymentAction"));
		final Object stripeAction = stripeTargetOf(bean);
		assertTrue(stripeAction instanceof StripeTakePaymentAction);
		assertTrue(delegateOf(stripeAction) instanceof DummyAction);
		assertNotNull("the Stripe action gets its services from the core context",
				((StripeTakePaymentAction) stripeAction).getModelService());

		final DummyAction action = (DummyAction) bean;
		assertEquals("OK", action.execute(process(order("proxy-take-1", null))));
		final OrderModel stripeOrder = order("proxy-take-2", "pi_proxy_take");
		assertEquals("a Stripe order without authorization is not captured", "NOK", action.execute(process(stripeOrder)));
		assertEquals(OrderStatus.PAYMENT_NOT_CAPTURED, stripeOrder.getStatus());
	}

	@Test
	public void proxiedCancelPaymentAdapterKeepsItsType() throws Exception
	{
		final Object bean = context.getBean("OmsOrderCancelPaymentServiceAdapter");

		assertTrue(bean instanceof DefaultOrderCancelPaymentServiceAdapter);
		assertTrue(AopUtils.isCglibProxy(bean));
		final Object stripeAdapter = stripeTargetOf(bean);
		assertTrue(stripeAdapter instanceof StripeOrderCancelPaymentServiceAdapter);
		assertTrue(delegateOf(stripeAdapter) instanceof DefaultOrderCancelPaymentServiceAdapter);
		assertTrue("Stripe orders reach the Stripe cancel strategy",
				((StripeOrderCancelPaymentServiceAdapter) stripeAdapter).getOrderCancelPaymentServiceStrategy()
						instanceof StripeOrderCancelPaymentServiceStrategy);
	}

	@Test
	public void tenantBeansKeepTheirType()
	{
		final ApplicationContext core = Registry.getCoreApplicationContext();
		final List<String> present = Arrays.stream(new String[] { "checkOrderAction", "takePaymentAction",
				"OmsOrderCancelPaymentServiceAdapter" }).filter(core::containsBean).toList();
		Assume.assumeFalse("no SAP order process in this tenant", present.isEmpty());

		for (final String name : present)
		{
			final Object bean = core.getBean(name);
			assertTrue(name + " is a class proxy", AopUtils.isCglibProxy(bean));
			assertTrue(name + " keeps the type of " + AopUtils.getTargetClass(bean),
					AopUtils.getTargetClass(bean).isInstance(bean));
			assertNotNull(name + " sends Stripe orders to Stripe", stripeTargetOf(bean));
		}
	}

	@Test
	public void tenantOrderProcessActionsRunStripeSteps() throws Exception
	{
		final ApplicationContext core = Registry.getCoreApplicationContext();
		Assume.assumeTrue("no SAP order process in this tenant",
				core.containsBean("checkOrderAction") && core.containsBean("takePaymentAction"));

		final AbstractSimpleDecisionAction<?> takePayment = (AbstractSimpleDecisionAction<?>) core.getBean(
				"takePaymentAction");
		final OrderModel unauthorized = order("tenant-take-1", "pi_tenant_take");
		assertEquals("a Stripe order without authorization is not captured", "NOK",
				execute(takePayment, process(unauthorized)));
		assertEquals(OrderStatus.PAYMENT_NOT_CAPTURED, unauthorized.getStatus());

		final AbstractSimpleDecisionAction<?> checkOrder = (AbstractSimpleDecisionAction<?>) core.getBean(
				"checkOrderAction");
		final OrderModel pending = order("tenant-check-1", "pi_tenant_check");
		try
		{
			execute(checkOrder, process(pending));
			fail("a Stripe order with a pending payment waits");
		}
		catch (final RetryLaterException expected)
		{
			assertEquals(OrderStatus.PAYMENT_PENDING, pending.getStatus());
		}
	}

	@SuppressWarnings("unchecked")
	private static String execute(final AbstractSimpleDecisionAction<?> action, final OrderProcessModel process)
			throws Exception
	{
		return ((AbstractSimpleDecisionAction<OrderProcessModel>) action).execute(process);
	}

	private OrderProcessModel process(final OrderModel order)
	{
		final OrderProcessModel process = modelService.create(OrderProcessModel.class);
		process.setOrder(order);
		return process;
	}

	private OrderModel order(final String code, final String paymentIntentId)
	{
		final OrderModel order = modelService.create(OrderModel.class);
		order.setCode(code);
		order.setUser(userService.getAdminUser());
		order.setCurrency(commonI18NService.getCurrentCurrency());
		order.setDate(new Date());
		order.setStatus(OrderStatus.CREATED);
		if (paymentIntentId != null)
		{
			final StripePaymentInfoModel paymentInfo = modelService.create(StripePaymentInfoModel.class);
			paymentInfo.setCode(code + "-payment");
			paymentInfo.setUser(userService.getAdminUser());
			paymentInfo.setStripePaymentIntentId(paymentIntentId);
			order.setPaymentInfo(paymentInfo);
		}
		modelService.save(order);
		return order;
	}

	private static Object stripeTargetOf(final Object proxy)
	{
		return Arrays.stream(((Advised) proxy).getAdvisors())
				.map(advisor -> advisor.getAdvice())
				.filter(StripeOrderRoutingInterceptor.class::isInstance)
				.map(advice -> ((StripeOrderRoutingInterceptor) advice).getStripeTarget())
				.findFirst()
				.orElse(null);
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
