package com.stripe.core.webhook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.stripe.Stripe;
import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.enums.StripeWebhookEventState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.model.StripeWebhookEventModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.order.StripeOrderPlacementService;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.payment.StripeTransactionLookup;
import com.stripe.core.testsupport.StripeSiteContextMocks;
import com.stripe.core.webhook.impl.DefaultStripePaymentIntentEventHandler;
import com.stripe.core.webhook.impl.DefaultStripeRefundEventHandler;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.core.PK;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.servicelayer.exceptions.SystemException;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.RetryLaterException;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskService;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

@UnitTest
public class StripeWebhookEventTaskRunnerTest
{
	private static final String PI_ID = "pi_123";
	private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");
	private static final Duration GRACE = Duration.ofSeconds(300);

	private ModelService modelService;
	private StripePaymentIntentRecordDao recordDao;
	private StripePaymentIntentRecordService recordService;
	private StripeGateway gateway;
	private StripePaymentIntentService paymentIntentService;
	private StripeOrderAuthorizationService authorizationService;
	private StripeOrderPlacementService placementService;
	private StripeOrderCancellationService cancellationService;
	private StripeTransactionLookup transactionLookup;
	private TaskService taskService;
	private StripeWebhookEventTaskRunner runner;
	private StripeWebhookEventModel event;
	private Instant eventCreated = NOW.minus(Duration.ofHours(2));
	private TaskModel task;
	private TimeService timeService;

	@Before
	public void setUp()
	{
		modelService = mock(ModelService.class);
		recordDao = mock(StripePaymentIntentRecordDao.class);
		recordService = mock(StripePaymentIntentRecordService.class);
		gateway = mock(StripeGateway.class);
		paymentIntentService = mock(StripePaymentIntentService.class);
		authorizationService = mock(StripeOrderAuthorizationService.class);
		placementService = mock(StripeOrderPlacementService.class);
		cancellationService = mock(StripeOrderCancellationService.class);
		transactionLookup = mock(StripeTransactionLookup.class);
		taskService = mock(TaskService.class);
		final StripeConfigurationService configurationService = mock(StripeConfigurationService.class);
		when(configurationService.getWebhookGracePeriod()).thenReturn(GRACE);
		timeService = mock(TimeService.class);
		when(timeService.getCurrentTime()).thenReturn(Date.from(NOW));
		when(recordService.findLiveOrder(anyString())).thenReturn(Optional.empty());

		final DefaultStripePaymentIntentEventHandler paymentIntentHandler = new DefaultStripePaymentIntentEventHandler();
		paymentIntentHandler.setStripeSiteContext(StripeSiteContextMocks.direct());
		paymentIntentHandler.setStripePaymentIntentRecordDao(recordDao);
		paymentIntentHandler.setStripePaymentIntentRecordService(recordService);
		paymentIntentHandler.setStripeGateway(gateway);
		paymentIntentHandler.setStripePaymentIntentService(paymentIntentService);
		paymentIntentHandler.setStripeConfigurationService(configurationService);
		paymentIntentHandler.setStripeOrderAuthorizationService(authorizationService);
		paymentIntentHandler.setStripeOrderPlacementService(placementService);
		paymentIntentHandler.setStripeOrderCancellationService(cancellationService);
		paymentIntentHandler.setModelService(modelService);
		paymentIntentHandler.setTimeService(timeService);

		final DefaultStripeRefundEventHandler refundHandler = new DefaultStripeRefundEventHandler();
		refundHandler.setStripeTransactionLookup(transactionLookup);
		refundHandler.setModelService(modelService);

		runner = new StripeWebhookEventTaskRunner();
		runner.setModelService(modelService);
		runner.setTimeService(timeService);
		runner.setStripePaymentIntentEventHandler(paymentIntentHandler);
		runner.setStripeRefundEventHandler(refundHandler);

		event = spy(new StripeWebhookEventModel());
		doReturn(PK.fromLong(7L)).when(event).getPk();
		event.setEventId("evt_1");
		event.setState(StripeWebhookEventState.RECEIVED);
		event.setAttempts(0);
		task = mock(TaskModel.class);
		when(task.getContextItem()).thenReturn(event);
	}

	@Test
	public void ignoresEventForUnknownPaymentIntent()
	{
		givenPaymentIntentEvent("payment_intent.succeeded", "succeeded");
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.empty());

		runner.run(taskService, task);

		assertEquals(StripeWebhookEventState.IGNORED, event.getState());
		verify(modelService).save(event);
		verifyNoInteractions(gateway, placementService, authorizationService);
	}

	@Test
	public void succeededEventAuthorizesPendingOrder()
	{
		givenPaymentIntentEvent("payment_intent.succeeded", "succeeded");
		final OrderModel order = new OrderModel();
		final StripePaymentIntentRecordModel record = givenRecord(order, Duration.ofHours(1));
		final PaymentIntent live = givenLivePaymentIntent("succeeded");
		when(authorizationService.authorizePendingOrder(order, live)).thenReturn(true);

		runner.run(taskService, task);

		verify(authorizationService).authorizePendingOrder(order, live);
		assertEquals(StripePaymentIntentState.CAPTURED, record.getState());
		verify(modelService).save(record);
		assertEquals(StripeWebhookEventState.PROCESSED, event.getState());
		verifyNoInteractions(placementService);
	}

	@Test
	public void succeededEventWithinGracePeriodRetriesLater()
	{
		givenPaymentIntentEvent("payment_intent.succeeded", "succeeded");
		givenRecord(null, Duration.ofSeconds(60));
		final TaskModel next = givenNewTask();

		runner.run(taskService, task);

		verify(next).setRunnerBean("stripeWebhookEventTaskRunner");
		verify(next).setContextItem(event);
		verify(next).setExecutionDate(Date.from(NOW.plusSeconds(240)));
		verify(taskService).scheduleTask(next);
		assertEquals(StripeWebhookEventState.RECEIVED, event.getState());
		assertEquals(Integer.valueOf(0), event.getAttempts());
		verify(modelService, never()).save(event);
		verifyNoInteractions(placementService, gateway);
	}

	@Test
	public void recentEventExtendsGracePeriodOfOldRecord()
	{
		eventCreated = NOW.minusSeconds(60);
		givenPaymentIntentEvent("payment_intent.succeeded", "succeeded");
		givenRecord(null, Duration.ofHours(1));
		final TaskModel next = givenNewTask();

		runner.run(taskService, task);

		verify(next).setExecutionDate(Date.from(NOW.plusSeconds(240)));
		verifyNoInteractions(placementService);
	}

	@Test
	public void succeededEventAfterGracePeriodPlacesOrder()
	{
		givenPaymentIntentEvent("payment_intent.succeeded", "succeeded");
		givenRecord(null, Duration.ofSeconds(301));
		final PaymentIntent live = givenLivePaymentIntent("succeeded");
		when(placementService.placeOrderFor(live)).thenReturn(Optional.of(new OrderModel()));

		runner.run(taskService, task);

		verify(placementService).placeOrderFor(live);
		assertEquals(StripeWebhookEventState.PROCESSED, event.getState());
	}

	@Test
	public void failedEventCancelsPendingOrder()
	{
		givenPaymentIntentEvent("payment_intent.payment_failed", "requires_payment_method");
		final OrderModel order = new OrderModel();
		final StripePaymentIntentRecordModel record = givenRecord(order, Duration.ofHours(1));
		givenLivePaymentIntent("requires_payment_method");
		when(authorizationService.hasAcceptedAuthorization(order, PI_ID)).thenReturn(false);

		runner.run(taskService, task);

		verify(cancellationService).cancelForFailedPayment(order);
		assertEquals(StripePaymentIntentState.FAILED, record.getState());
		assertEquals(StripeWebhookEventState.PROCESSED, event.getState());
	}

	@Test
	public void refundEventUpdatesRefundEntry()
	{
		final JsonObject refund = new JsonObject();
		refund.addProperty("id", "re_1");
		refund.addProperty("object", "refund");
		refund.addProperty("status", "failed");
		refund.addProperty("payment_intent", PI_ID);
		givenEvent("refund.updated", refund, Stripe.API_VERSION);
		final PaymentTransactionEntryModel entry = new PaymentTransactionEntryModel();
		entry.setTransactionStatus(TransactionStatus.ACCEPTED.name());
		when(transactionLookup.findRefundEntries("re_1")).thenReturn(List.of(entry));

		runner.run(taskService, task);

		assertEquals(TransactionStatus.REJECTED.name(), entry.getTransactionStatus());
		verify(modelService).save(entry);
		assertEquals(StripeWebhookEventState.PROCESSED, event.getState());
	}

	@Test
	public void retryableErrorRetriesWithBackoff()
	{
		givenPaymentIntentEvent("payment_intent.succeeded", "succeeded");
		givenRecord(new OrderModel(), Duration.ofHours(1));
		when(gateway.retrievePaymentIntent(PI_ID)).thenThrow(retryableError());

		final long[] expected = { Duration.ofSeconds(30).toMillis(), Duration.ofMinutes(2).toMillis(),
				Duration.ofMinutes(8).toMillis(), Duration.ofMinutes(30).toMillis() };
		for (int retry = 0; retry < expected.length; retry++)
		{
			when(task.getRetry()).thenReturn(retry);
			try
			{
				runner.run(taskService, task);
				fail("expected RetryLaterException");
			}
			catch (final RetryLaterException e)
			{
				assertEquals(expected[retry], e.getDelay());
				assertEquals(RetryLaterException.Method.LINEAR, e.getMethod());
				assertTrue(e.isRollBack());
			}
		}
		assertEquals(StripeWebhookEventState.RECEIVED, event.getState());
		verify(modelService, never()).save(event);
	}

	@Test
	public void fifthFailureMarksEventFailed()
	{
		givenPaymentIntentEvent("payment_intent.succeeded", "succeeded");
		givenRecord(new OrderModel(), Duration.ofHours(1));
		when(task.getRetry()).thenReturn(4);
		final StripeGatewayException error = retryableError();
		when(gateway.retrievePaymentIntent(PI_ID)).thenThrow(error);

		assertSame(error, runFailing());
		verify(modelService, never()).save(event);

		runner.handleError(taskService, task, new SystemException(error));

		assertEquals(StripeWebhookEventState.FAILED, event.getState());
		assertEquals(Integer.valueOf(5), event.getAttempts());
		assertTrue(event.getLastError().contains("Stripe is not reachable"));
		verify(modelService).save(event);
	}

	@Test
	public void nonRetryableErrorMarksEventFailedAtOnce()
	{
		givenPaymentIntentEvent("payment_intent.succeeded", "succeeded");
		givenRecord(new OrderModel(), Duration.ofHours(1));
		when(gateway.retrievePaymentIntent(PI_ID))
				.thenThrow(new StripeGatewayException("No such payment_intent", "resource_missing", false, false, null));

		final RuntimeException error = runFailing();
		runner.handleError(taskService, task, new SystemException(error));

		assertEquals(StripeWebhookEventState.FAILED, event.getState());
		assertEquals(Integer.valueOf(1), event.getAttempts());
	}

	@Test
	public void eventNoLongerReceivedIsSkipped()
	{
		givenPaymentIntentEvent("payment_intent.succeeded", "succeeded");
		event.setState(StripeWebhookEventState.PROCESSED);

		runner.run(taskService, task);

		final InOrder order = inOrder(modelService);
		order.verify(modelService).lock(PK.fromLong(7L));
		order.verify(modelService).refresh(event);
		verify(modelService, never()).save(any());
		verifyNoInteractions(recordDao, gateway);
	}

	@Test
	public void eventLockFailureIsRetried()
	{
		givenPaymentIntentEvent("payment_intent.succeeded", "succeeded");
		doThrow(new IllegalStateException("lock wait timeout")).when(modelService).lock(any(PK.class));

		try
		{
			runner.run(taskService, task);
			fail("expected RetryLaterException");
		}
		catch (final RetryLaterException e)
		{
			assertEquals(Duration.ofSeconds(30).toMillis(), e.getDelay());
			assertTrue(e.isRollBack());
		}
		assertEquals(StripeWebhookEventState.RECEIVED, event.getState());
		verify(modelService, never()).save(event);
		verifyNoInteractions(recordDao, gateway);
	}

	@Test
	public void databaseWithoutRowLocksStillProcessesEvent()
	{
		givenPaymentIntentEvent("payment_intent.succeeded", "succeeded");
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.empty());
		doThrow(new UnsupportedOperationException("HSQLDB doesn't support explicit row locking")).when(modelService)
				.lock(any(PK.class));

		runner.run(taskService, task);

		verify(modelService).refresh(event);
		assertEquals(StripeWebhookEventState.IGNORED, event.getState());
	}

	@Test
	public void otherEventTypeIsIgnored()
	{
		givenPaymentIntentEvent("payment_intent.created", "requires_payment_method");

		runner.run(taskService, task);

		assertEquals(StripeWebhookEventState.IGNORED, event.getState());
		verifyNoInteractions(recordDao, gateway);
	}

	@Test
	public void eventOfOtherApiVersionIsProcessed()
	{
		final JsonObject paymentIntent = paymentIntentJson("succeeded");
		givenEvent("payment_intent.succeeded", paymentIntent, "2020-08-27");
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.empty());

		runner.run(taskService, task);

		verify(recordDao).findByPaymentIntentId(PI_ID);
		assertEquals(StripeWebhookEventState.IGNORED, event.getState());
	}

	@Test
	public void unreadablePayloadMarksEventFailedWithoutPayloadInError()
	{
		event.setType("payment_intent.succeeded");
		event.setPayload("{\"id\":\"evt_1\",\"data\":{\"object\":\"secret-like-text\"");

		final RuntimeException error = runFailing();
		runner.handleError(taskService, task, new SystemException(error));

		assertEquals(StripeWebhookEventState.FAILED, event.getState());
		assertFalse(event.getLastError().contains("secret-like-text"));
	}

	@Test
	public void unreadableEventFailsWithoutRetry()
	{
		givenEvent("payment_intent.succeeded", paymentIntentJson("succeeded"), null);

		final RuntimeException error = runFailing();
		runner.handleError(taskService, task, new SystemException(error));

		assertTrue(error instanceof StripeWebhookException);
		assertEquals(StripeWebhookEventState.FAILED, event.getState());
		assertEquals(Integer.valueOf(1), event.getAttempts());
		assertTrue(event.getLastError().contains("cannot be read"));
		verifyNoInteractions(recordDao, gateway);
	}

	@Test
	public void handleErrorMarksReceivedEventFailed()
	{
		when(task.getRetry()).thenReturn(2);

		runner.handleError(taskService, task, new SystemException(new IllegalStateException("commit failed")));

		assertEquals(StripeWebhookEventState.FAILED, event.getState());
		assertEquals(Integer.valueOf(3), event.getAttempts());
		assertTrue(event.getLastError().contains("commit failed"));
		verify(modelService).refresh(event);
		verify(modelService).save(event);
	}

	@Test
	public void taskWithoutEventDoesNothing()
	{
		when(task.getContextItem()).thenReturn(null);

		runner.run(taskService, task);

		verifyNoInteractions(modelService, recordDao);
		assertNull(event.getLastError());
	}

	private RuntimeException runFailing()
	{
		try
		{
			runner.run(taskService, task);
		}
		catch (final RetryLaterException e)
		{
			fail("expected a failure without retry, got RetryLaterException");
		}
		catch (final RuntimeException e)
		{
			return e;
		}
		fail("expected a failure");
		return null;
	}

	private static StripeGatewayException retryableError()
	{
		return new StripeGatewayException("Stripe is not reachable", "api_connection_error", true, false, null);
	}

	private TaskModel givenNewTask()
	{
		final TaskModel next = mock(TaskModel.class);
		when(modelService.create(TaskModel.class)).thenReturn(next);
		return next;
	}

	private void givenPaymentIntentEvent(final String type, final String status)
	{
		givenEvent(type, paymentIntentJson(status), Stripe.API_VERSION);
	}

	private JsonObject paymentIntentJson(final String status)
	{
		final JsonObject paymentIntent = new JsonObject();
		paymentIntent.addProperty("id", PI_ID);
		paymentIntent.addProperty("object", "payment_intent");
		paymentIntent.addProperty("status", status);
		paymentIntent.addProperty("amount", 1999);
		paymentIntent.addProperty("currency", "eur");
		return paymentIntent;
	}

	private void givenEvent(final String type, final JsonObject object, final String apiVersion)
	{
		final JsonObject data = new JsonObject();
		data.add("object", object);
		final JsonObject json = new JsonObject();
		json.addProperty("id", "evt_1");
		json.addProperty("object", "event");
		json.addProperty("api_version", apiVersion);
		json.addProperty("type", type);
		json.addProperty("created", eventCreated.getEpochSecond());
		json.add("data", data);
		event.setType(type);
		event.setPayload(json.toString());
	}

	private StripePaymentIntentRecordModel givenRecord(final OrderModel order, final Duration age)
	{
		final StripePaymentIntentRecordModel record = new StripePaymentIntentRecordModel();
		record.setPaymentIntentId(PI_ID);
		record.setOrder(order);
		record.setState(StripePaymentIntentState.CREATED);
		record.setCreationtime(Date.from(NOW.minus(age)));
		when(recordDao.findByPaymentIntentId(PI_ID)).thenReturn(Optional.of(record));
		return record;
	}

	private PaymentIntent givenLivePaymentIntent(final String status)
	{
		final PaymentIntent live = new PaymentIntent();
		live.setId(PI_ID);
		live.setStatus(status);
		when(gateway.retrievePaymentIntent(PI_ID)).thenReturn(live);
		return live;
	}
}
