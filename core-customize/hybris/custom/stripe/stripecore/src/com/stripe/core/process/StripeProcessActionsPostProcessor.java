package com.stripe.core.process;

import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Adds the Stripe steps to the order process of SAP, whichever extension defines it (order management, the classic
 * fulfilment process or a copy of it). It works on bean names, not classes:
 * <ul>
 * <li>{@code checkOrderAction} is wrapped by {@link StripeAwaitPaymentCheckOrderAction};</li>
 * <li>{@code takePaymentAction} is wrapped by {@link StripeTakePaymentAction};</li>
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
	static final String AWAIT_PAYMENT_WRAPPER = "stripeAwaitPaymentCheckOrderAction";
	static final String TAKE_PAYMENT_WRAPPER = "stripeTakePaymentAction";
	static final String PAYMENT_PENDING_DISPLAY = "paymentPending";

	private BeanFactory beanFactory;

	@Override
	public Object postProcessAfterInitialization(final Object bean, final String beanName)
	{
		switch (beanName)
		{
			case CHECK_ORDER_ACTION:
				return wrap(bean, beanName, AWAIT_PAYMENT_WRAPPER, StripeAwaitPaymentCheckOrderAction.class);
			case TAKE_PAYMENT_ACTION:
				return wrap(bean, beanName, TAKE_PAYMENT_WRAPPER, StripeTakePaymentAction.class);
			case STATUS_DISPLAY_MAP:
				return bean instanceof Map<?, ?> map ? withPaymentPending(map) : bean;
			default:
				return bean;
		}
	}

	private Object wrap(final Object bean, final String beanName, final String wrapperName, final Class<?> wrapperClass)
	{
		if (wrapperClass.isInstance(bean))
		{
			return bean;
		}
		if (!(bean instanceof AbstractSimpleDecisionAction))
		{
			LOG.warn("Bean {} is a {}, not an AbstractSimpleDecisionAction: the Stripe step is not added", beanName,
					bean.getClass().getName());
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
