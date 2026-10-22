package com.stripe.core.webhook.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.order.StripeOrderPlacementService;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.payment.impl.DefaultStripePaymentDetailsCopier;
import com.stripe.core.site.StripeSiteContext;
import com.stripe.core.testsupport.StripeSiteContextMocks;
import com.stripe.core.webhook.StripeWebhookException;
import com.stripe.core.webhook.StripeWebhookOutcome;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderModel;
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
public class DefaultStripePaymentIntentEventHandlerTest
{
	private static final String PI_ID = "pi_456";
	private static final String SUCCEEDED = "payment_intent.succeeded";
	private static final String FAILED = "payment_intent.payment_failed";
	private static final String REQUIRES_ACTION = "payment_intent.requires_action";
	private static final String PARTIALLY_FUNDED = "payment_intent.partially_funded";
	private static final PK RECORD_PK = PK.fromLong(42L);
	private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

	private StripePaymentIntentRecordDao recordDao;
	private StripePaymentIntentRecordService recordService;
	private StripeGateway gateway;
	private StripePaymentIntentService paymentIntentService;
	private StripeOrderAuthorizationService authorizationService;
	private StripeOrderPlacementService placementService;
	private StripeOrderCancellationService cancellationService;
	private ModelService modelService;
	private StripeSiteContext siteContext;
	private DefaultStripePaymentIntentEventHandler handler;
	private PaymentIntent eventObject;
	private Instant eventCreated;

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
		siteContext = StripeSiteContextMocks.direct();
		handler.setStripeSiteContext(siteContext);
		final DefaultStripePaymentDetailsCopier detailsCopier = new DefaultStripePaymentDetailsCopier();
		detailsCopier.setModelService(modelService);
		handler.setStripePaymentDetailsCopier(detailsCopier);

		eventObject = new PaymentIntent();
		eventObject.setId(PI_ID);
		eventCreated = NOW.minus(Duration.ofHours(1));
	}

	@Test
	public void unlinkedLiveOrderIsLinkedAndAuthorized()
	{
		final StripePaymentIntentRecordModel record = givenRecord(null, StripePaymentIntentState.PROCESSING, Duration.ofHours(1));
		final OrderModel order = new OrderModel();
		when(recordService.findLiveOrder(PI_ID)).thenReturn(Optional.of(order));
		final PaymentIntent live = givenLive("requires_capture");
		when(authorizationService.authorizePendingOrder(order, live)).thenReturn(true);

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(SUCCEEDED, eventObject, eventCreated));

		verify(recordService).linkOrder(PI_ID, order);
		verify(authorizationService).authorizePendingOrder(order, live);
		assertEquals(StripePaymentIntentState.AUTHORIZED, record.getState());
		verifyNoInteractions(placementService);
	}

	@Test
	public void eventIsHandledInTheSiteOfTheRecord() throws Exception
	{
		final StripePaymentIntentRecordModel record = givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofDays(1));
		record.setBaseSiteUid("site-b");
		final PaymentIntent live = givenLive("requires_capture");
		when(placementService.placeOrderFor(live)).thenReturn(Optional.of(new OrderModel()));

		handler.handle(SUCCEEDED, eventObject, eventCreated);

		final InOrder inOrder = inOrder(siteContext, gateway);
		inOrder.verify(siteContext).runInSite(eq("site-b"), any());
		inOrder.verify(gateway).retrievePaymentIntent(PI_ID);
	}

	@Test
	public void paymentOfCancelledOrderReleasesFunds()
	{
		final OrderModel order = new OrderModel();
		order.setCode("order-2");
		order.setStatus(OrderStatus.CANCELLED);
		givenRecord(order, StripePaymentIntentState.PROCESSING, Duration.ofDays(2));
		givenLive("succeeded");

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(SUCCEEDED, eventObject, eventCreated));

		verify(paymentIntentService).releaseFunds(PI_ID, "order order-2 is cancelled");
		verify(authorizationService, never()).authorizePendingOrder(any(), any());
	}

	@Test
	public void processingEventKeepsOrderPending()
	{
		final StripePaymentIntentRecordModel record = givenRecord(new OrderModel(), StripePaymentIntentState.CREATED,
				Duration.ofHours(1));
		givenLive("processing");

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle("payment_intent.processing", eventObject, eventCreated));

		verify(authorizationService, never()).authorizePendingOrder(any(), any());
		assertEquals(StripePaymentIntentState.PROCESSING, record.getState());
	}

	@Test
	public void rejectedAuthorizationCancelsOrderAndReleasesFunds()
	{
		final OrderModel order = new OrderModel();
		order.setCode("order-1");
		givenRecord(order, StripePaymentIntentState.PROCESSING, Duration.ofHours(1));
		final PaymentIntent live = givenLive("succeeded");
		when(authorizationService.authorizePendingOrder(order, live)).thenReturn(false);

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(SUCCEEDED, eventObject, eventCreated));

		verify(cancellationService).cancelForFailedPayment(order);
		verify(paymentIntentService).releaseFunds(PI_ID, "authorization-rejected");
	}

	@Test
	public void recentEventOfOldRecordWaitsForGracePeriod()
	{
		givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofHours(1));

		try
		{
			handler.handle(SUCCEEDED, eventObject, NOW.minusSeconds(60));
			fail("expected RetryLaterException");
		}
		catch (final RetryLaterException e)
		{
			assertEquals(Duration.ofSeconds(240).toMillis(), e.getDelay());
		}
		verifyNoInteractions(placementService, gateway);
	}

	@Test
	public void oldEventOfOldRecordPlacesOrder()
	{
		givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofHours(1));
		final PaymentIntent live = givenLive("succeeded");
		when(placementService.placeOrderFor(live)).thenReturn(Optional.of(new OrderModel()));

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(SUCCEEDED, eventObject, NOW.minusSeconds(400)));

		verify(placementService).placeOrderFor(live);
	}

	@Test
	public void releasedFundsStopOrderPlacement()
	{
		givenRecord(null, StripePaymentIntentState.REFUNDED, Duration.ofHours(1));

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(SUCCEEDED, eventObject, eventCreated));

		verifyNoInteractions(placementService, gateway);
	}

	@Test
	public void releasedFundsStopAuthorizationOfPendingOrder()
	{
		givenRecord(new OrderModel(), StripePaymentIntentState.CANCELED, Duration.ofHours(1));

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(SUCCEEDED, eventObject, eventCreated));

		verifyNoInteractions(authorizationService, gateway, paymentIntentService);
	}

	@Test
	public void recordIsLockedBeforeAnyDecision()
	{
		final StripePaymentIntentRecordModel record = givenRecord(new OrderModel(), StripePaymentIntentState.PROCESSING,
				Duration.ofHours(1));
		givenLive("processing");

		handler.handle("payment_intent.processing", eventObject, eventCreated);

		final InOrder order = inOrder(modelService, gateway);
		order.verify(modelService).lock(RECORD_PK);
		order.verify(modelService).refresh(record);
		order.verify(gateway).retrievePaymentIntent(PI_ID);
	}

	@Test
	public void placementHeldByOtherPathRetries()
	{
		final StripePaymentIntentRecordModel record = givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofHours(1));
		final PaymentIntent live = givenLive("succeeded");
		when(placementService.placeOrderFor(live)).thenReturn(Optional.empty());

		try
		{
			handler.handle(SUCCEEDED, eventObject, eventCreated);
			fail("expected StripeWebhookException");
		}
		catch (final StripeWebhookException e)
		{
			assertTrue(e.isRetryable());
		}
		verify(modelService, times(2)).refresh(record);
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

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(SUCCEEDED, eventObject, eventCreated));
	}

	@Test
	public void noOrderIsPlacedForPaymentThatIsNoLongerAlive()
	{
		final StripePaymentIntentRecordModel record = givenRecord(null, StripePaymentIntentState.PROCESSING, Duration.ofHours(1));
		givenLive("requires_payment_method");

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle("payment_intent.processing", eventObject, eventCreated));

		verifyNoInteractions(placementService);
		assertEquals(StripePaymentIntentState.FAILED, record.getState());
	}

	@Test(expected = IllegalStateException.class)
	public void missingPlacementServiceFails()
	{
		handler.setStripeOrderPlacementService(null);
		givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofHours(1));
		givenLive("succeeded");

		handler.handle(SUCCEEDED, eventObject, eventCreated);
	}

	@Test
	public void staleFailureEventIsIgnored()
	{
		final OrderModel order = new OrderModel();
		final StripePaymentIntentRecordModel record = givenRecord(order, StripePaymentIntentState.CAPTURED, Duration.ofHours(1));
		givenLive("succeeded");

		assertEquals(StripeWebhookOutcome.IGNORED, handler.handle(FAILED, eventObject, eventCreated));

		verifyNoInteractions(cancellationService);
		assertEquals(StripePaymentIntentState.CAPTURED, record.getState());
	}

	@Test
	public void failureWithoutOrderOnlyUpdatesRecord()
	{
		final StripePaymentIntentRecordModel record = givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofSeconds(5));
		givenLive("canceled");

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle("payment_intent.canceled", eventObject, eventCreated));

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

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle("payment_intent.canceled", eventObject, eventCreated));

		verifyNoInteractions(cancellationService);
	}

	@Test
	public void paymentOfCancelledOrderWithAcceptedAuthorizationDoesNotReleaseFunds()
	{
		final OrderModel order = new OrderModel();
		order.setStatus(OrderStatus.CANCELLED);
		givenRecord(order, StripePaymentIntentState.AUTHORIZED, Duration.ofHours(1));
		final PaymentIntent live = givenLive("requires_capture");
		when(authorizationService.hasAcceptedAuthorization(order, PI_ID)).thenReturn(true);
		when(authorizationService.authorizePendingOrder(order, live)).thenReturn(true);

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(SUCCEEDED, eventObject, eventCreated));

		verifyNoInteractions(paymentIntentService);
	}

	@Test
	public void requiresActionWithVoucherPlacesOrderAfterGracePeriod()
	{
		withInstructions(eventObject, "multibanco_display_details");
		givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofHours(1));
		try
		{
			handler.handle(REQUIRES_ACTION, eventObject, NOW.minusSeconds(60));
			fail("expected RetryLaterException");
		}
		catch (final RetryLaterException e)
		{
			assertEquals(Duration.ofSeconds(240).toMillis(), e.getDelay());
		}
		verifyNoInteractions(placementService);

		final PaymentIntent live = withInstructions(givenLive("requires_action"), "multibanco_display_details");
		when(placementService.placeOrderFor(live)).thenReturn(Optional.of(new OrderModel()));

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(REQUIRES_ACTION, eventObject, NOW.minusSeconds(400)));

		verify(placementService).placeOrderFor(live);
	}

	@Test
	public void requiresActionForThreeDsIsIgnored()
	{
		withInstructions(eventObject, "use_stripe_sdk");
		givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofHours(1));

		assertEquals(StripeWebhookOutcome.IGNORED, handler.handle(REQUIRES_ACTION, eventObject, NOW.minusSeconds(400)));

		verifyNoInteractions(placementService, gateway, recordDao);
	}

	@Test
	public void requiresActionWithInstructionsOfPlacedOrderRecordsAwaitingFunds()
	{
		withInstructions(eventObject, "display_bank_transfer_instructions");
		final StripePaymentIntentRecordModel record = givenRecord(new OrderModel(), StripePaymentIntentState.CREATED,
				Duration.ofHours(1));
		withInstructions(givenLive("requires_action"), "display_bank_transfer_instructions");

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(REQUIRES_ACTION, eventObject, eventCreated));

		assertEquals(StripePaymentIntentState.AWAITING_FUNDS, record.getState());
		verifyNoInteractions(placementService);
		verify(authorizationService, never()).authorizePendingOrder(any(), any());
	}

	@Test
	public void partiallyFundedUpdatesAmountRemaining()
	{
		final StripePaymentInfoModel orderPaymentInfo = new StripePaymentInfoModel();
		orderPaymentInfo.setAmountRemaining(12100L);
		final OrderModel order = new OrderModel();
		order.setPaymentInfo(orderPaymentInfo);
		final StripePaymentIntentRecordModel record = givenRecord(order, StripePaymentIntentState.AWAITING_FUNDS,
				Duration.ofHours(1));
		final PaymentIntent live = withInstructions(givenLive("requires_action"), "display_bank_transfer_instructions");
		live.getNextAction().getDisplayBankTransferInstructions().setAmountRemaining(5100L);

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(PARTIALLY_FUNDED, eventObject, eventCreated));

		assertEquals(Long.valueOf(5100L), orderPaymentInfo.getAmountRemaining());
		verify(modelService).save(orderPaymentInfo);
		assertEquals(StripePaymentIntentState.AWAITING_FUNDS, record.getState());
		verify(modelService, never()).save(record);
		verifyNoInteractions(placementService, cancellationService, paymentIntentService);
		verify(authorizationService, never()).authorizePendingOrder(any(), any());
	}

	@Test
	public void partiallyFundedWithoutOrderPlacesItAfterGracePeriod()
	{
		givenRecord(null, StripePaymentIntentState.CREATED, Duration.ofHours(1));
		final PaymentIntent live = withInstructions(givenLive("requires_action"), "display_bank_transfer_instructions");
		when(placementService.placeOrderFor(live)).thenReturn(Optional.of(new OrderModel()));

		assertEquals(StripeWebhookOutcome.PROCESSED, handler.handle(PARTIALLY_FUNDED, eventObject, NOW.minusSeconds(400)));

		verify(placementService).placeOrderFor(live);
	}

	private static PaymentIntent withInstructions(final PaymentIntent paymentIntent, final String nextActionType)
	{
		paymentIntent.setStatus("requires_action");
		final PaymentIntent.NextAction nextAction = new PaymentIntent.NextAction();
		nextAction.setType(nextActionType);
		if ("display_bank_transfer_instructions".equals(nextActionType))
		{
			nextAction.setDisplayBankTransferInstructions(new PaymentIntent.NextAction.DisplayBankTransferInstructions());
		}
		paymentIntent.setNextAction(nextAction);
		return paymentIntent;
	}

	private StripePaymentIntentRecordModel givenRecord(final OrderModel order, final StripePaymentIntentState state,
			final Duration age)
	{
		final StripePaymentIntentRecordModel record = spy(new StripePaymentIntentRecordModel());
		doReturn(RECORD_PK).when(record).getPk();
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
