package com.stripe.core.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderCancellationService;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.order.payment.CreditCardPaymentInfoModel;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction.Transition;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.RetryLaterException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

@UnitTest
public class StripeAwaitPaymentCheckOrderActionTest
{
	private static final String PI_ID = "pi_await";
	private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
	private static final Duration TIMEOUT = Duration.ofHours(24);
	private static final Duration RETRY = Duration.ofMinutes(5);

	private AbstractSimpleDecisionAction<OrderProcessModel> delegate;
	private StripePaymentIntentRecordDao recordDao;
	private StripeOrderAuthorizationService authorizationService;
	private StripeOrderCancellationService cancellationService;
	private ModelService modelService;
	private StripeAwaitPaymentCheckOrderAction action;
	private OrderModel order;
	private OrderProcessModel process;

	@Before
	@SuppressWarnings("unchecked")
	public void setUp() throws Exception
	{
		delegate = mock(AbstractSimpleDecisionAction.class);
		recordDao = mock(StripePaymentIntentRecordDao.class);
		authorizationService = mock(StripeOrderAuthorizationService.class);
		cancellationService = mock(StripeOrderCancellationService.class);
		modelService = mock(ModelService.class);
		final StripeConfigurationService configurationService = mock(StripeConfigurationService.class);
		when(configurationService.getPendingPaymentTimeout()).thenReturn(TIMEOUT);
		when(configurationService.getPendingPaymentRetryDelay()).thenReturn(RETRY);
		final TimeService timeService = mock(TimeService.class);
		when(timeService.getCurrentTime()).thenReturn(Date.from(NOW));

		action = new StripeAwaitPaymentCheckOrderAction(delegate);
		action.setModelService(modelService);
		action.setStripePaymentIntentRecordDao(recordDao);
		action.setStripeOrderAuthorizationService(authorizationService);
		action.setStripeOrderCancellationService(cancellationService);
		action.setStripeConfigurationService(configurationService);
		action.setTimeService(timeService);

		final StripePaymentInfoModel paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId(PI_ID);
		order = new OrderModel();
		order.setCode("order-1");
		order.setPaymentInfo(paymentInfo);
		order.setStatus(OrderStatus.CREATED);
		order.setDate(Date.from(NOW.minus(Duration.ofHours(1))));
		process = new OrderProcessModel();
		process.setOrder(order);
		when(delegate.executeAction(process)).thenReturn(Transition.OK);
	}

	@Test
	public void pendingPaymentRetriesLaterAndSetsPaymentPending() throws Exception
	{
		final StripePaymentIntentRecordModel record = givenRecord(StripePaymentIntentState.PROCESSING);

		try
		{
			action.execute(process);
			fail("RetryLaterException expected");
		}
		catch (final RetryLaterException e)
		{
			assertEquals(RETRY.toMillis(), e.getDelay());
			assertEquals(RetryLaterException.Method.LINEAR, e.getMethod());
			assertEquals("the PAYMENT_PENDING status must be committed", false, e.isRollBack());
		}

		assertEquals(OrderStatus.PAYMENT_PENDING, order.getStatus());
		final InOrder inOrder = inOrder(modelService, authorizationService);
		inOrder.verify(modelService).lock(record.getPk());
		inOrder.verify(authorizationService).hasAcceptedAuthorization(order, PI_ID);
		verify(modelService).save(order);
		verifyNoInteractions(cancellationService);
		verify(delegate, never()).executeAction(any());
	}

	@Test
	public void acceptedPaymentDelegates() throws Exception
	{
		givenRecord(StripePaymentIntentState.AUTHORIZED);
		when(authorizationService.hasAcceptedAuthorization(order, PI_ID)).thenReturn(true);

		assertEquals("OK", action.execute(process));

		verify(delegate).executeAction(process);
		verifyNoInteractions(cancellationService);
	}

	@Test
	public void expiredPendingPaymentCancelsOrder() throws Exception
	{
		givenRecord(StripePaymentIntentState.PROCESSING);
		order.setDate(Date.from(NOW.minus(TIMEOUT)));

		assertEquals("NOK", action.execute(process));

		verify(cancellationService).cancelForFailedPayment(order);
		verify(delegate, never()).executeAction(any());
	}

	@Test
	public void failedPaymentCancelsOrder() throws Exception
	{
		givenRecord(StripePaymentIntentState.FAILED);

		assertEquals("NOK", action.execute(process));

		verify(cancellationService).cancelForFailedPayment(order);
		verify(delegate, never()).executeAction(any());
	}

	@Test
	public void canceledPaymentCancelsOrder() throws Exception
	{
		givenRecord(StripePaymentIntentState.CANCELED);

		assertEquals("NOK", action.execute(process));

		verify(cancellationService).cancelForFailedPayment(order);
	}

	@Test
	public void refundedPaymentCancelsOrder() throws Exception
	{
		givenRecord(StripePaymentIntentState.REFUNDED);

		assertEquals("NOK", action.execute(process));

		verify(cancellationService).cancelForFailedPayment(order);
	}

	@Test
	public void cancelledOrderEndsWithoutDelegate() throws Exception
	{
		givenRecord(StripePaymentIntentState.CANCELED);
		order.setStatus(OrderStatus.CANCELLED);
		when(authorizationService.hasAcceptedAuthorization(order, PI_ID)).thenReturn(true);

		assertEquals("NOK", action.execute(process));

		verifyNoInteractions(cancellationService);
		verify(delegate, never()).executeAction(any());
	}

	@Test
	public void nonStripeOrderDelegates() throws Exception
	{
		order.setPaymentInfo(new CreditCardPaymentInfoModel());

		assertEquals("OK", action.execute(process));

		verify(delegate).executeAction(process);
		verifyNoInteractions(recordDao, authorizationService, cancellationService);
	}

	private StripePaymentIntentRecordModel givenRecord(final StripePaymentIntentState state)
	{
		final StripePaymentIntentRecordModel record = mock(StripePaymentIntentRecordModel.class);
		when(record.getPk()).thenReturn(PK.fromLong(77L));
		when(record.getState()).thenReturn(state);
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(record));
		return record;
	}
}
