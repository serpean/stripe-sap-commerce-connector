package com.stripe.core.webhook;

import com.google.gson.JsonParseException;
import com.stripe.core.enums.StripeWebhookEventState;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripeWebhookEventModel;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiResource;
import de.hybris.platform.servicelayer.exceptions.SystemException;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.RetryLaterException;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskRunner;
import de.hybris.platform.task.TaskService;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes a stored Stripe webhook event (the context item of the task) and marks it PROCESSED, IGNORED or FAILED.
 * <p>
 * The task engine runs {@link #run} in one database transaction. The runner locks the event row first, so two tasks of
 * one event never run at the same time, and it skips an event that is no longer RECEIVED. A failed lock (for example
 * a lock wait timeout) is a temporary error like the others.
 * <p>
 * Failures: the attempt number comes from the engine ({@code task.retry}, which survives a rollback). A temporary
 * error rolls back all the work of the attempt and gives a {@link RetryLaterException} after 30 s, 2 min, 8 min and
 * 30 min. The fifth failed attempt, or a permanent error, is thrown on: the engine rolls the attempt back and calls
 * {@link #handleError}, which marks the event FAILED with {@code attempts} and {@code lastError} outside that
 * transaction. So no partial work of a failed attempt is ever committed.
 * <p>
 * The grace period of a payment without order is not a failure: the runner schedules a new task for the end of the
 * period and finishes this one, so the new task starts with no counted attempt. The log and {@code lastError} never
 * hold the payload or a secret.
 */
public class StripeWebhookEventTaskRunner implements TaskRunner<TaskModel>
{
	static final String RUNNER_BEAN = "stripeWebhookEventTaskRunner";
	static final int MAX_ATTEMPTS = 5;
	static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(8),
			Duration.ofMinutes(30));

	private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookEventTaskRunner.class);
	private static final int MAX_ERROR_LENGTH = 255;

	private ModelService modelService;
	private TimeService timeService;
	private StripePaymentIntentEventHandler stripePaymentIntentEventHandler;
	private StripeRefundEventHandler stripeRefundEventHandler;

	@Override
	public void run(final TaskService taskService, final TaskModel task) throws RetryLaterException
	{
		final Optional<StripeWebhookEventModel> found = eventOf(task);
		if (found.isEmpty())
		{
			LOG.warn("Task {} has no Stripe webhook event: nothing to do", task.getPk());
			return;
		}
		final StripeWebhookEventModel event = found.get();
		final StripeWebhookOutcome outcome;
		try
		{
			StripeRowLocks.lockAndRefresh(modelService, event);
			if (!StripeWebhookEventState.RECEIVED.equals(event.getState()))
			{
				LOG.info("Stripe webhook {} ({}) is already {}: nothing to do", event.getEventId(), event.getType(),
						event.getState());
				return;
			}
			outcome = dispatch(event);
		}
		catch (final RetryLaterException gracePeriod)
		{
			scheduleAfterGracePeriod(taskService, event, Duration.ofMillis(gracePeriod.getDelay()));
			return;
		}
		catch (final RuntimeException e)
		{
			throw retryOrGiveUp(event, task, e);
		}
		event.setState(outcome == StripeWebhookOutcome.PROCESSED ? StripeWebhookEventState.PROCESSED
				: StripeWebhookEventState.IGNORED);
		modelService.save(event);
		LOG.info("Stripe webhook {} ({}) is {}", event.getEventId(), event.getType(), event.getState());
	}

	/**
	 * Called by the task engine after it rolled back a failed {@link #run}: the fifth failed attempt, a permanent error,
	 * or a failed commit. Marks a RECEIVED event FAILED.
	 */
	@Override
	public void handleError(final TaskService taskService, final TaskModel task, final Throwable error)
	{
		eventOf(task).ifPresent(event -> {
			modelService.refresh(event);
			if (StripeWebhookEventState.RECEIVED.equals(event.getState()))
			{
				event.setAttempts(attemptOf(task));
				event.setLastError(summary(unwrap(error)));
				event.setState(StripeWebhookEventState.FAILED);
				modelService.save(event);
				LOG.error("Stripe webhook {} ({}) failed after {} attempt(s): {}", event.getEventId(), event.getType(),
						event.getAttempts(), event.getLastError());
			}
		});
	}

	private RuntimeException retryOrGiveUp(final StripeWebhookEventModel event, final TaskModel task,
			final RuntimeException error)
	{
		final int attempt = attemptOf(task);
		if (!isRetryable(error) || attempt >= MAX_ATTEMPTS)
		{
			LOG.warn("Stripe webhook {} ({}) failed, attempt {} of {}, no retry: {}", event.getEventId(), event.getType(),
					attempt, MAX_ATTEMPTS, summary(error));
			return error;
		}
		final Duration delay = BACKOFF.get(attempt - 1);
		LOG.warn("Stripe webhook {} ({}) failed, attempt {} of {}; next attempt in {} s: {}", event.getEventId(),
				event.getType(), attempt, MAX_ATTEMPTS, delay.toSeconds(), summary(error));
		final RetryLaterException retry = new RetryLaterException("Stripe webhook " + event.getEventId() + " failed", error);
		retry.setMethod(RetryLaterException.Method.LINEAR);
		retry.setDelay(delay.toMillis());
		retry.setRollBack(true);
		return retry;
	}

	private void scheduleAfterGracePeriod(final TaskService taskService, final StripeWebhookEventModel event,
			final Duration wait)
	{
		final TaskModel next = modelService.create(TaskModel.class);
		next.setRunnerBean(RUNNER_BEAN);
		next.setContextItem(event);
		next.setExecutionDate(Date.from(timeService.getCurrentTime().toInstant().plus(wait)));
		taskService.scheduleTask(next);
		LOG.info("Stripe webhook {} ({}) waits {} s for the customer", event.getEventId(), event.getType(),
				wait.toSeconds());
	}

	private StripeWebhookOutcome dispatch(final StripeWebhookEventModel event) throws RetryLaterException
	{
		final String type = StringUtils.defaultString(event.getType());
		switch (type)
		{
			case "payment_intent.succeeded":
			case "payment_intent.amount_capturable_updated":
			case "payment_intent.processing":
			case "payment_intent.payment_failed":
			case "payment_intent.canceled":
				final Event stripeEvent = parse(event);
				final StripeObject paymentIntent = dataObjectOf(event, stripeEvent);
				if (paymentIntent instanceof PaymentIntent)
				{
					final Instant created = stripeEvent.getCreated() == null ? null
							: Instant.ofEpochSecond(stripeEvent.getCreated());
					return stripePaymentIntentEventHandler.handle(type, (PaymentIntent) paymentIntent, created);
				}
				throw new StripeWebhookException("Event " + event.getEventId() + " holds no payment intent", false);
			case "charge.refunded":
			case "refund.updated":
				return stripeRefundEventHandler.handle(dataObjectOf(event, parse(event)));
			default:
				LOG.debug("Stripe webhook {} has type {}: ignored", event.getEventId(), type);
				return StripeWebhookOutcome.IGNORED;
		}
	}

	private Event parse(final StripeWebhookEventModel event)
	{
		try
		{
			final Event stripeEvent = StringUtils.isBlank(event.getPayload()) ? null
					: ApiResource.GSON.fromJson(event.getPayload(), Event.class);
			if (stripeEvent == null || stripeEvent.getData() == null)
			{
				throw unreadable(event, null);
			}
			return stripeEvent;
		}
		catch (final JsonParseException e)
		{
			throw unreadable(event, e);
		}
	}

	/**
	 * The object of the event. When the API version of the event is not the version of the library, the object is read
	 * with {@code deserializeUnsafe}.
	 */
	private StripeObject dataObjectOf(final StripeWebhookEventModel event, final Event stripeEvent)
	{
		final EventDataObjectDeserializer deserializer = stripeEvent.getDataObjectDeserializer();
		final Optional<StripeObject> object = deserializer.getObject();
		if (object.isPresent())
		{
			return object.get();
		}
		try
		{
			return deserializer.deserializeUnsafe();
		}
		catch (final EventDataObjectDeserializationException | JsonParseException e)
		{
			throw unreadable(event, e);
		}
	}

	private static StripeWebhookException unreadable(final StripeWebhookEventModel event, final Exception cause)
	{
		return new StripeWebhookException("The payload of event " + event.getEventId() + " cannot be read", false, cause);
	}

	private static boolean isRetryable(final RuntimeException error)
	{
		if (error instanceof StripeGatewayException)
		{
			return ((StripeGatewayException) error).isRetryable();
		}
		if (error instanceof StripeWebhookException)
		{
			return ((StripeWebhookException) error).isRetryable();
		}
		return true;
	}

	/**
	 * The number of the current attempt: the engine counts the retries already done.
	 */
	private static int attemptOf(final TaskModel task)
	{
		return (task.getRetry() == null ? 0 : task.getRetry()) + 1;
	}

	private static Throwable unwrap(final Throwable error)
	{
		Throwable cause = error;
		while (cause instanceof SystemException && cause.getCause() != null)
		{
			cause = cause.getCause();
		}
		return cause;
	}

	private static String summary(final Throwable error)
	{
		return StringUtils.abbreviate(error.getClass().getSimpleName() + ": " + StringUtils.defaultString(error.getMessage()),
				MAX_ERROR_LENGTH);
	}

	private static Optional<StripeWebhookEventModel> eventOf(final TaskModel task)
	{
		return task.getContextItem() instanceof StripeWebhookEventModel
				? Optional.of((StripeWebhookEventModel) task.getContextItem()) : Optional.empty();
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public void setTimeService(final TimeService timeService)
	{
		this.timeService = timeService;
	}

	public void setStripePaymentIntentEventHandler(final StripePaymentIntentEventHandler stripePaymentIntentEventHandler)
	{
		this.stripePaymentIntentEventHandler = stripePaymentIntentEventHandler;
	}

	public void setStripeRefundEventHandler(final StripeRefundEventHandler stripeRefundEventHandler)
	{
		this.stripeRefundEventHandler = stripeRefundEventHandler;
	}
}
