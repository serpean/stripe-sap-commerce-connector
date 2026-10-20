package com.stripe.core.webhook.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.order.StripeOrderPlacementService;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.webhook.StripeWebhookException;
import com.stripe.core.webhook.StripeWebhookOutcome;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.time.TimeService;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class DefaultStripePaymentIntentEventHandlerTest
{
	private static final String PI_ID = "pi_456";
	private static final String SUCCEEDED = "payment_intent.succeeded";
	private static final String FAILED = "payment_intent.payment_failed";
	private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

	private StripePaymentIntentRecordDao recordDao;
	private StripePaymentIntentRecordService recordService;
	private StripeGateway gateway;
	private StripePaymentIntentService paymentIntentService;
	private StripeOrderAuthorizationService authorizationService;
	private StripeOrderPlacementService placementService;
	private StripeOrderCancellationService cancellationService;
	private ModelService modelService;
	private DefaultStripePaymentIntentEventHandler handler;
	private PaymentIntent eventObject;

	@Before
	public void setUp()
	{
		recordDao = mock(StripePaymentIntentRecordDao.class);
		recordService = mock(StripePaymentIntentRecordService.class);
		gateway = mock(StripeGateway.class);
		paymentIntentService = mock(StripePaymentIntentService.class);
		authorizationService = mock(StripeOrderAuthorizationService.class);
		placementService = mock(StripeOrderPlacementService.class);
		cancellationService = mock(StripeOrderCancellationService.class);
		modelService = mock(ModelService.class);
		final StripeConfigurationService configurationService = mock(StripeConfigurationService.class);
		when(configurationService.getWebhookGracePeriod()).thenReturn(Duration.ofSeconds(300));
		final TimeService timeService = mock(TimeService.class);
		when(timeService.getCurrentTime()).thenReturn(Date.from(NOW));
		when(recordService.findLiveOrder(PI_ID)).thenReturn(Optional.empty());

		handler = new DefaultStripePaymentIntentEventHandler();
		handler.setStripePaymentIntentRecordDao(recordDao);
		handler.setStripePaymentIntentRecordService(recordService);
		handler.setStripeGateway(gateway);
		handler.setStripePaymentIntentService(paymentIntentService);
		handler.setStripeConfigurationService(configurationService);
		handler.setStripeOrderAuthorizationService(authorizationService);
		handler.setStripeOrderPlacementService(placementService);
		handler.setStripeOrderCancellationService(cancellationService);
		handler.setModelService(modelService);
		handler.setTimeService(timeService);

		eventObject = new PaymentIntent();
		eventObject.setId(PI_ID);
	}

	@Test
	public void unlinkedLiveOrderIsLinkedAndAuthorized()
	{
		final StripePaymentIntentRecordModel record = givenRecord(null, StripePaymentIntentState.PROCESSING, Duration.ofHours(1));
		final OrderModel order = new OrderModel();
		when(recordService.findLiveOrder(PI_ID)).thenReturn(Optional.of(order));
		final PaymentIntent live = givenLive("requires_capture");
		when(authorizationService.authorizePendingOrder(order, live)).thenReturn(true);

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(SUCCEEDED, eventObject));

		verify(recordService).linkOrder(PI_ID, order);
		verify(authorizationService).authorizePendingOrder(order, live);
		assertEquals(StripePaymentIntentState.AUTHORIZED, record.getState());
		verifyNoInteractions(placementService);
	}

	@Test
	public void paymentOfCancelledOrderReleasesFunds()
	{
		final OrderModel order = new OrderModel();
		order.setCode("order-2");
		order.setStatus(OrderStatus.CANCELLED);
		givenRecord(order, StripePaymentIntentState.PROCESSING, Duration.ofDays(2));
		givenLive("succeeded");

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(SUCCEEDED, eventObject));

		verify(paymentIntentService).releaseFunds(PI_ID, "order order-2 is cancelled");
		verify(authorizationService, never()).authorizePendingOrder(any(), any());
	}

	@Test
	public void processingEventKeepsOrderPending()
	{
		final StripePaymentIntentRecordModel record = givenRecord(new OrderModel(), StripePaymentIntentState.CREATED,
				Duration.ofHours(1));
		givenLive("processing");

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle("payment_intent.processing", eventObject));

		verify(authorizationService, never()).authorizePendingOrder(any(), any());
		assertEquals(StripePaymentIntentState.PROCESSING, record.getState());
	}

	@Test
	public void rejectedAuthorizationFailsWithoutRetry()
	{
		final OrderModel order = new OrderModel();
		order.setCode("order-1");
		givenRecord(order, StripePaymentIntentState.PROCESSING, Duration.ofHours(1));
		final PaymentIntent live = givenLive("succeeded");
		when(authorizationService.authorizePendingOrder(order, live)).thenReturn(false);

		try
		{
			handler.handle(SUCCEEDED, eventObject);
			fail("expected StripeWebhookException");
		}
		catch (final StripeWebhookException e)
		{
			assertFalse(e.isRetryable());
			assertTrue(e.getMessage().contains("order-1"));
		}
	}

	@Test
	public void placementHeldByOtherPathRetries()
	{
		final StripePaymentIntentRecordModel record = givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofHours(1));
		final PaymentIntent live = givenLive("succeeded");
		when(placementService.placeOrderFor(live)).thenReturn(Optional.empty());

		try
		{
			handler.handle(SUCCEEDED, eventObject);
			fail("expected StripeWebhookException");
		}
		catch (final StripeWebhookException e)
		{
			assertTrue(e.isRetryable());
		}
		verify(modelService).refresh(record);
	}

	@Test
	public void placementThatReleasedFundsIsProcessed()
	{
		final StripePaymentIntentRecordModel record = givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofHours(1));
		final PaymentIntent live = givenLive("succeeded");
		when(placementService.placeOrderFor(live)).thenAnswer(invocation -> {
			record.setState(StripePaymentIntentState.REFUNDED);
			return Optional.empty();
		});

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(SUCCEEDED, eventObject));
	}

	@Test
	public void noOrderIsPlacedForPaymentThatIsNoLongerAlive()
	{
		final StripePaymentIntentRecordModel record = givenRecord(null, StripePaymentIntentState.PROCESSING, Duration.ofHours(1));
		givenLive("requires_payment_method");

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle("payment_intent.processing", eventObject));

		verifyNoInteractions(placementService);
		assertEquals(StripePaymentIntentState.FAILED, record.getState());
	}

	@Test(expected = IllegalStateException.class)
	public void missingPlacementServiceFails()
	{
		handler.setStripeOrderPlacementService(null);
		givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofHours(1));
		givenLive("succeeded");

		handler.handle(SUCCEEDED, eventObject);
	}

	@Test
	public void staleFailureEventIsIgnored()
	{
		final OrderModel order = new OrderModel();
		final StripePaymentIntentRecordModel record = givenRecord(order, StripePaymentIntentState.CAPTURED, Duration.ofHours(1));
		givenLive("succeeded");

		assertEquals(StripeWebhookOutcome.IGNORED, handler.handle(FAILED, eventObject));

		verifyNoInteractions(cancellationService);
		assertEquals(StripePaymentIntentState.CAPTURED, record.getState());
	}

	@Test
	public void failureWithoutOrderOnlyUpdatesRecord()
	{
		final StripePaymentIntentRecordModel record = givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofSeconds(5));
		givenLive("canceled");

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle("payment_intent.canceled", eventObject));

		assertEquals(StripePaymentIntentState.CANCELED, record.getState());
		verify(modelService).save(record);
		verifyNoInteractions(cancellationService);
	}

	@Test
	public void failureOfAuthorizedOrderDoesNotCancelIt()
	{
		final OrderModel order = new OrderModel();
		givenRecord(order, StripePaymentIntentState.AUTHORIZED, Duration.ofHours(1));
		givenLive("canceled");
		when(authorizationService.hasAcceptedAuthorization(order, PI_ID)).thenReturn(true);

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle("payment_intent.canceled", eventObject));

		verifyNoInteractions(cancellationService);
	}

	@Test
	public void refundedRecordKeepsItsState()
	{
		final StripePaymentIntentRecordModel record = givenRecord(new OrderModel(), StripePaymentIntentState.REFUNDED,
				Duration.ofHours(1));
		final PaymentIntent live = givenLive("succeeded");
		when(authorizationService.authorizePendingOrder(any(), any())).thenReturn(true);

		handler.handle(SUCCEEDED, eventObject);

		verify(authorizationService).authorizePendingOrder(record.getOrder(), live);
		assertEquals(StripePaymentIntentState.REFUNDED, record.getState());
	}

	private StripePaymentIntentRecordModel givenRecord(final OrderModel order, final StripePaymentIntentState state,
			final Duration age)
	{
		final StripePaymentIntentRecordModel record = new StripePaymentIntentRecordModel();
		record.setPaymentIntentId(PI_ID);
		record.setOrder(order);
		record.setState(state);
		record.setCreationtime(Date.from(NOW.minus(age)));
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(record));
		return record;
	}

	private PaymentIntent givenLive(final String status)
	{
		final PaymentIntent live = new PaymentIntent();
		live.setId(PI_ID);
		live.setStatus(status);
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(live);
		return live;
	}
}
