package com.stripe.core.process;

import com.stripe.core.order.StripeOrderCancelPaymentServiceAdapter;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.ordercancel.OrderCancelPaymentServiceAdapter;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Adds the Stripe steps to the order process of SAP, whichever extension defines it (order management, the classic
 * fulfilment process or a copy of it). It works on bean names and their aliases, not classes:
 * <ul>
 * <li>{@code checkOrderAction} is wrapped by {@link StripeAwaitPaymentCheckOrderAction};</li>
 * <li>{@code takePaymentAction} is wrapped by {@link StripeTakePaymentAction};</li>
 * <li>{@code OmsOrderCancelPaymentServiceAdapter} (order management) is wrapped by
 * {@link StripeOrderCancelPaymentServiceAdapter}, so a cancel of a Stripe order reaches the cancel payment strategy;</li>
 * <li>{@code acceleratorStatusDisplayMap} gets the display value of {@code PAYMENT_PENDING}.</li>
 * </ul>
 * A bean that does not exist is not changed. The wrappers are prototype beans, created with the original bean as
 * constructor argument.
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
			return wrap(bean, beanName, AWAIT_PAYMENT_WRAPPER, StripeAwaitPaymentCheckOrderAction.class,
					AbstractSimpleDecisionAction.class);
		}
		if (names.contains(TAKE_PAYMENT_ACTION))
		{
			return wrap(bean, beanName, TAKE_PAYMENT_WRAPPER, StripeTakePaymentAction.class,
					AbstractSimpleDecisionAction.class);
		}
		if (names.contains(OMS_CANCEL_PAYMENT_ADAPTER))
		{
			return wrap(bean, beanName, CANCEL_PAYMENT_ADAPTER_WRAPPER, StripeOrderCancelPaymentServiceAdapter.class,
					OrderCancelPaymentServiceAdapter.class);
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

	private Object wrap(final Object bean, final String beanName, final String wrapperName, final Class<?> wrapperClass,
			final Class<?> requiredType)
	{
		if (wrapperClass.isInstance(bean))
		{
			return bean;
		}
		if (!requiredType.isInstance(bean))
		{
			LOG.warn("Bean {} is a {}, not a {}: the Stripe step is not added", beanName, bean.getClass().getName(),
					requiredType.getSimpleName());
			return bean;
		}
		LOG.info("Wrapping bean {} ({}) with {}", beanName, bean.getClass().getName(), wrapperClass.getSimpleName());
		return beanFactory.getBean(wrapperName, bean);
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
