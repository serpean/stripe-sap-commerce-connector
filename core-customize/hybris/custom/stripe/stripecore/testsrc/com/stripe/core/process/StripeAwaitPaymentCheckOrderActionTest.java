package com.stripe.core.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.payment.StripePaymentIntentChecks;
import com.stripe.core.site.StripeSiteContext;
import com.stripe.core.testsupport.StripeSiteContextMocks;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
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
	private StripeGateway gateway;
	private StripeSiteContext siteContext;
	private BaseSiteModel site;
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
		gateway = mock(StripeGateway.class);
		action.setStripeGateway(gateway);
		final StripePaymentIntentChecks checks = new StripePaymentIntentChecks();
		checks.setStripeAmountConverter(mock(StripeAmountConverter.class));
		action.setStripePaymentIntentChecks(checks);
		siteContext = StripeSiteContextMocks.direct();
		action.setStripeSiteContext(siteContext);
		site = new BaseSiteModel();
		site.setUid("site-f");

		final StripePaymentInfoModel paymentInfo = new StripePaymentInfoModel();
		paymentInfo.setStripePaymentIntentId(PI_ID);
		order = new OrderModel();
		order.setCode("order-1");
		order.setPaymentInfo(paymentInfo);
		order.setStatus(OrderStatus.CREATED);
		order.setSite(site);
		order.setDate(Date.from(NOW.minus(Duration.ofHours(1))));
		process = new OrderProcessModel();
		process.setOrder(order);
		when(delegate.executeAction(process)).thenReturn(Transition.OK);
	}

	@Test
	public void pendingPaymentRetriesLaterAndSetsPaymentPending() throws Exception
	{
		final StripePaymentIntentRecordModel record = givenRecord(StripePaymentIntentState.PROCESSING);
		givenLive("processing");

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
		givenLive("processing");
		order.setDate(Date.from(NOW.minus(TIMEOUT)));

		assertEquals("NOK", action.execute(process));

		verify(cancellationService).cancelForFailedPayment(order);
		verify(delegate, never()).executeAction(any());
	}

	@Test
	public void failedPaymentCancelsOrder() throws Exception
	{
		givenRecord(StripePaymentIntentState.FAILED);
		givenLive("requires_payment_method");

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
	public void canceledLivePaymentCancelsOrder() throws Exception
	{
		givenRecord(StripePaymentIntentState.PROCESSING);
		givenLive("canceled");

		assertEquals("NOK", action.execute(process));

		verify(cancellationService).cancelForFailedPayment(order);
	}

	@Test
	public void paymentThatSucceededWithoutWebhookAuthorizesTheOrderInsteadOfCancellingIt() throws Exception
	{
		givenRecord(StripePaymentIntentState.FAILED);
		order.setDate(Date.from(NOW.minus(TIMEOUT)));
		final PaymentIntent live = givenLive("requires_capture");
		when(authorizationService.authorizePendingOrder(order, live)).thenReturn(true);

		assertEquals("OK", action.execute(process));

		verify(authorizationService).authorizePendingOrder(order, live);
		verify(delegate).executeAction(process);
		verifyNoInteractions(cancellationService);
	}

	@Test
	public void capturedPaymentWithAllMoneyAuthorizesTheOrder() throws Exception
	{
		givenRecord(StripePaymentIntentState.PROCESSING);
		final PaymentIntent live = givenLive("succeeded");
		live.setAmount(1999L);
		live.setAmountReceived(1999L);
		live.setLatestChargeObject(charge(0L));
		when(authorizationService.authorizePendingOrder(order, live)).thenReturn(true);

		assertEquals("OK", action.execute(process));

		verifyNoInteractions(cancellationService);
	}

	@Test
	public void capturedPaymentThatWasRefundedCancelsTheOrder() throws Exception
	{
		givenRecord(StripePaymentIntentState.PROCESSING);
		final PaymentIntent live = givenLive("succeeded");
		live.setAmount(1999L);
		live.setAmountReceived(1999L);
		live.setLatestChargeObject(charge(1999L));

		assertEquals("NOK", action.execute(process));

		verify(authorizationService, never()).authorizePendingOrder(any(), any());
		verify(cancellationService).cancelForFailedPayment(order);
	}

	@Test
	public void rejectedAuthorizationOfThePaymentCancelsTheOrder() throws Exception
	{
		givenRecord(StripePaymentIntentState.PROCESSING);
		final PaymentIntent live = givenLive("requires_capture");
		when(authorizationService.authorizePendingOrder(order, live)).thenReturn(false);

		assertEquals("NOK", action.execute(process));

		verify(cancellationService).cancelForFailedPayment(order);
		verify(delegate, never()).executeAction(any());
	}

	@Test
	public void unreachableStripeAtTheTimeoutRetriesInsteadOfCancelling() throws Exception
	{
		givenRecord(StripePaymentIntentState.PROCESSING);
		order.setDate(Date.from(NOW.minus(TIMEOUT)));
		when(gateway.retrievePaymentIntent(PI_ID))
				.thenThrow(new StripeGatewayException("down", "api_connection_error", true, false, null));

		try
		{
			action.execute(process);
			fail("RetryLaterException expected");
		}
		catch (final RetryLaterException e)
		{
			assertEquals(RETRY.toMillis(), e.getDelay());
		}

		verifyNoInteractions(cancellationService);
	}

	@Test
	public void decisionRunsInTheSiteOfTheOrder() throws Exception
	{
		givenRecord(StripePaymentIntentState.PROCESSING);
		givenLive("canceled");

		action.execute(process);

		final InOrder inOrder = inOrder(siteContext, gateway);
		inOrder.verify(siteContext).runInSite(same(site), any());
		inOrder.verify(gateway).retrievePaymentIntent(PI_ID);
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

	private PaymentIntent givenLive(final String status)
	{
		final PaymentIntent live = new PaymentIntent();
		live.setId(PI_ID);
		live.setStatus(status);
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(live);
		return live;
	}

	private static Charge charge(final long refunded)
	{
		final Charge charge = new Charge();
		charge.setAmountRefunded(refunded);
		return charge;
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
