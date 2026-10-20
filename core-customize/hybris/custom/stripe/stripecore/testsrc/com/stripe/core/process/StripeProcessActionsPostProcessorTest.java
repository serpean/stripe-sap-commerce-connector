package com.stripe.core.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.BeanFactory;

@UnitTest
public class StripeProcessActionsPostProcessorTest
{
	private BeanFactory beanFactory;
	private StripeProcessActionsPostProcessor postProcessor;

	@Before
	public void setUp()
	{
		beanFactory = mock(BeanFactory.class);
		postProcessor = new StripeProcessActionsPostProcessor();
		postProcessor.setBeanFactory(beanFactory);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void postProcessorWrapsOnlyNamedBeans()
	{
		final AbstractSimpleDecisionAction<OrderProcessModel> checkOrder = mock(AbstractSimpleDecisionAction.class);
		final AbstractSimpleDecisionAction<OrderProcessModel> takePayment = mock(AbstractSimpleDecisionAction.class);
		final AbstractSimpleDecisionAction<OrderProcessModel> other = mock(AbstractSimpleDecisionAction.class);
		final StripeAwaitPaymentCheckOrderAction awaitWrapper = new StripeAwaitPaymentCheckOrderAction(checkOrder);
		final StripeTakePaymentAction takeWrapper = new StripeTakePaymentAction(takePayment);
		when(beanFactory.getBean("stripeAwaitPaymentCheckOrderAction", checkOrder)).thenReturn(awaitWrapper);
		when(beanFactory.getBean("stripeTakePaymentAction", takePayment)).thenReturn(takeWrapper);

		assertSame(awaitWrapper, postProcessor.postProcessAfterInitialization(checkOrder, "checkOrderAction"));
		assertSame(takeWrapper, postProcessor.postProcessAfterInitialization(takePayment, "takePaymentAction"));
		assertSame(other, postProcessor.postProcessAfterInitialization(other, "fraudCheckOrderAction"));
	}

	@Test
	public void wrapperIsNotWrappedTwice()
	{
		@SuppressWarnings("unchecked")
		final StripeTakePaymentAction wrapper = new StripeTakePaymentAction(mock(AbstractSimpleDecisionAction.class));

		assertSame(wrapper, postProcessor.postProcessAfterInitialization(wrapper, "takePaymentAction"));
		verifyNoInteractions(beanFactory);
	}

	@Test
	public void beanOfOtherTypeIsKept()
	{
		final Object bean = new Object();

		assertSame(bean, postProcessor.postProcessAfterInitialization(bean, "checkOrderAction"));
		verifyNoInteractions(beanFactory);
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
}
