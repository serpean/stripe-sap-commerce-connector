package com.stripe.core.process;

import com.stripe.core.order.StripeOrderCancelPaymentServiceAdapter;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.ordercancel.OrderCancelPaymentServiceAdapter;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Adds the Stripe steps to the order process of SAP, whichever extension defines it (order management, the classic
 * fulfilment process or a copy of it). It works on bean names and their aliases, not classes:
 * <ul>
 * <li>{@code checkOrderAction}: Stripe orders go to {@link StripeAwaitPaymentCheckOrderAction};</li>
 * <li>{@code takePaymentAction}: Stripe orders go to {@link StripeTakePaymentAction};</li>
 * <li>{@code OmsOrderCancelPaymentServiceAdapter} (order management): Stripe orders go to
 * {@link StripeOrderCancelPaymentServiceAdapter}, so a cancel of a Stripe order reaches the cancel payment strategy;</li>
 * <li>{@code acceleratorStatusDisplayMap} gets the display value of {@code PAYMENT_PENDING}.</li>
 * </ul>
 * The SAP bean is replaced by a class proxy of itself ({@link StripeOrderRoutingInterceptor}), so the bean keeps its
 * type and other beans can still inject it by its class. When the bean cannot be proxied (a final class, or a final
 * intercepted method), the Stripe bean replaces it and a warning is logged. A bean that does not exist is not
 * changed. The Stripe beans are prototype beans, created with the original bean as constructor argument: other orders
 * go to it.
 */
public class StripeProcessActionsPostProcessor implements BeanPostProcessor, BeanFactoryAware
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeProcessActionsPostProcessor.class);

	static final String CHECK_ORDER_ACTION = "checkOrderAction";
	static final String TAKE_PAYMENT_ACTION = "takePaymentAction";
	static final String STATUS_DISPLAY_MAP = "acceleratorStatusDisplayMap";
	static final String OMS_CANCEL_PAYMENT_ADAPTER = "OmsOrderCancelPaymentServiceAdapter";
	static final String CANCEL_PAYMENT_ADAPTER_WRAPPER = "stripeOrderCancelPaymentServiceAdapter";
	static final String AWAIT_PAYMENT_WRAPPER = "stripeAwaitPaymentCheckOrderAction";
	static final String TAKE_PAYMENT_WRAPPER = "stripeTakePaymentAction";
	static final String PAYMENT_PENDING_DISPLAY = "paymentPending";

	private BeanFactory beanFactory;

	@Override
	public Object postProcessAfterInitialization(final Object bean, final String beanName)
	{
		final Set<String> names = namesOf(beanName);
		if (names.contains(CHECK_ORDER_ACTION))
		{
			return addStripeStep(bean, beanName, AWAIT_PAYMENT_WRAPPER, StripeAwaitPaymentCheckOrderAction.class,
					AbstractSimpleDecisionAction.class, StripeProcessActionsPostProcessor::actionInterceptor);
		}
		if (names.contains(TAKE_PAYMENT_ACTION))
		{
			return addStripeStep(bean, beanName, TAKE_PAYMENT_WRAPPER, StripeTakePaymentAction.class,
					AbstractSimpleDecisionAction.class, StripeProcessActionsPostProcessor::actionInterceptor);
		}
		if (names.contains(OMS_CANCEL_PAYMENT_ADAPTER))
		{
			return addStripeStep(bean, beanName, CANCEL_PAYMENT_ADAPTER_WRAPPER,
					StripeOrderCancelPaymentServiceAdapter.class, OrderCancelPaymentServiceAdapter.class,
					stripeBean -> StripeOrderRoutingInterceptor
							.forCancelPaymentAdapter((OrderCancelPaymentServiceAdapter) stripeBean));
		}
		if (names.contains(STATUS_DISPLAY_MAP) && bean instanceof Map<?, ?> map)
		{
			return withPaymentPending(map);
		}
		return bean;
	}

	/**
	 * The bean name and its aliases. SAP extensions override a bean with an alias ({@code <alias name="custom..."
	 * alias="takePaymentAction"/>}), and Spring post-processes the bean with its own name.
	 */
	private Set<String> namesOf(final String beanName)
	{
		final Set<String> names = new HashSet<>();
		names.add(beanName);
		final String[] aliases = beanFactory.getAliases(beanName);
		if (aliases != null)
		{
			names.addAll(Arrays.asList(aliases));
		}
		return names;
	}

	private Object addStripeStep(final Object bean, final String beanName, final String stripeBeanName,
			final Class<?> stripeBeanClass, final Class<?> requiredType,
			final Function<Object, StripeOrderRoutingInterceptor> interceptorFactory)
	{
		if (stripeBeanClass.isInstance(bean) || hasStripeInterceptor(bean))
		{
			return bean;
		}
		if (!requiredType.isInstance(bean))
		{
			LOG.warn("Bean {} is a {}, not a {}: the Stripe step is not added", beanName, bean.getClass().getName(),
					requiredType.getSimpleName());
			return bean;
		}
		final Object stripeBean = beanFactory.getBean(stripeBeanName, bean);
		if (Modifier.isFinal(bean.getClass().getModifiers()))
		{
			return replacedBy(stripeBean, bean, beanName, "the class is final");
		}
		final StripeOrderRoutingInterceptor interceptor = interceptorFactory.apply(stripeBean);
		final Optional<Method> finalMethod = interceptor.finalInterceptedMethod(bean.getClass());
		if (finalMethod.isPresent())
		{
			return replacedBy(stripeBean, bean, beanName, "the method " + finalMethod.get() + " is final");
		}
		try
		{
			final ProxyFactory proxyFactory = new ProxyFactory(bean);
			proxyFactory.setProxyTargetClass(true);
			proxyFactory.addAdvice(interceptor);
			final Object proxy = proxyFactory.getProxy();
			LOG.info("Bean {} ({}) sends Stripe orders to {}", beanName, bean.getClass().getName(),
					stripeBeanClass.getSimpleName());
			return proxy;
		}
		catch (final RuntimeException e)
		{
			return replacedBy(stripeBean, bean, beanName, e.getMessage());
		}
	}

	private static Object replacedBy(final Object stripeBean, final Object bean, final String beanName,
			final String reason)
	{
		LOG.warn("Bean {} ({}) cannot be proxied ({}): it is replaced by {}, so it loses its type", beanName,
				bean.getClass().getName(), reason, stripeBean.getClass().getSimpleName());
		return stripeBean;
	}

	private static boolean hasStripeInterceptor(final Object bean)
	{
		return bean instanceof Advised advised && Arrays.stream(advised.getAdvisors())
				.map(Advisor::getAdvice)
				.anyMatch(StripeOrderRoutingInterceptor.class::isInstance);
	}

	@SuppressWarnings("unchecked")
	private static StripeOrderRoutingInterceptor actionInterceptor(final Object stripeAction)
	{
		return StripeOrderRoutingInterceptor.forAction((AbstractSimpleDecisionAction<OrderProcessModel>) stripeAction);
	}

	private static Map<Object, Object> withPaymentPending(final Map<?, ?> displayMap)
	{
		final Map<Object, Object> result = new LinkedHashMap<>(displayMap);
		result.putIfAbsent(OrderStatus.PAYMENT_PENDING.getCode(), PAYMENT_PENDING_DISPLAY);
		return result;
	}

	@Override
	public void setBeanFactory(final BeanFactory beanFactory)
	{
		this.beanFactory = beanFactory;
	}
}
