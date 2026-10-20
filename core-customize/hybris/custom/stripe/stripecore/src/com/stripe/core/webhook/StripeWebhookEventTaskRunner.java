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
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.task.RetryLaterException;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskRunner;
import de.hybris.platform.task.TaskService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes a stored Stripe webhook event (the context item of the task) and marks it PROCESSED, IGNORED or FAILED.
 * <p>
 * The task engine runs this runner in a database transaction. The runner locks the event row first, so two tasks of
 * one event never run at the same time, and it skips an event that is no longer RECEIVED.
 * <p>
 * A failed attempt increments {@code attempts} and keeps a short {@code lastError}. A temporary error gives a
 * {@link RetryLaterException} after 30 s, 2 min, 8 min and 30 min; the fifth failed attempt, or a permanent error, marks
 * the event FAILED. The retry does not roll back, so that the attempt counter is kept. The grace period of a payment
 * without order is not a failure: it retries without counting an attempt. The log and {@code lastError} never hold
 * the payload or a secret.
 */
public class StripeWebhookEventTaskRunner implements TaskRunner<TaskModel>
{
	static final int MAX_ATTEMPTS = 5;
	static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(8),
			Duration.ofMinutes(30));

	private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookEventTaskRunner.class);
	private static final int MAX_ERROR_LENGTH = 255;

	private ModelService modelService;
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
		modelService.lock(event);
		modelService.refresh(event);
		if (!StripeWebhookEventState.RECEIVED.equals(event.getState()))
		{
			LOG.info("Stripe webhook {} ({}) is already {}: nothing to do", event.getEventId(), event.getType(),
					event.getState());
			return;
		}

		final StripeWebhookOutcome outcome;
		try
		{
			outcome = dispatch(event);
		}
		catch (final RetryLaterException e)
		{
			throw e;
		}
		catch (final RuntimeException e)
		{
			recordFailure(event, e);
			return;
		}
		event.setState(outcome == StripeWebhookOutcome.PROCESSED ? StripeWebhookEventState.PROCESSED
				: StripeWebhookEventState.IGNORED);
		modelService.save(event);
		LOG.info("Stripe webhook {} ({}) is {}", event.getEventId(), event.getType(), event.getState());
	}

	/**
	 * Called by the task engine, outside the rolled-back transaction, when {@link #run} or its commit failed.
	 */
	@Override
	public void handleError(final TaskService taskService, final TaskModel task, final Throwable error)
	{
		eventOf(task).ifPresent(event -> {
			modelService.refresh(event);
			if (StripeWebhookEventState.RECEIVED.equals(event.getState()))
			{
				event.setAttempts(attemptsOf(event) + 1);
				event.setLastError(summary(error));
				event.setState(StripeWebhookEventState.FAILED);
				modelService.save(event);
				LOG.error("Stripe webhook {} ({}) failed: {}", event.getEventId(), event.getType(), event.getLastError());
			}
		});
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
				final StripeObject paymentIntent = dataObjectOf(event);
				if (paymentIntent instanceof PaymentIntent)
				{
					return stripePaymentIntentEventHandler.handle(type, (PaymentIntent) paymentIntent);
				}
				throw new StripeWebhookException("Event " + event.getEventId() + " holds no payment intent", false);
			case "charge.refunded":
			case "refund.updated":
				return stripeRefundEventHandler.handle(dataObjectOf(event));
			default:
				LOG.debug("Stripe webhook {} has type {}: ignored", event.getEventId(), type);
				return StripeWebhookOutcome.IGNORED;
		}
	}

	/**
	 * The object of the event. When the API version of the event is not the version of the library, the object is read
	 * with {@code deserializeUnsafe}.
	 */
	private StripeObject dataObjectOf(final StripeWebhookEventModel event)
	{
		final String unreadable = "The payload of event " + event.getEventId() + " cannot be read";
		try
		{
			final Event stripeEvent = StringUtils.isBlank(event.getPayload()) ? null
					: ApiResource.GSON.fromJson(event.getPayload(), Event.class);
			if (stripeEvent == null || stripeEvent.getData() == null)
			{
				throw new StripeWebhookException(unreadable, false);
			}
			final EventDataObjectDeserializer deserializer = stripeEvent.getDataObjectDeserializer();
			final Optional<StripeObject> object = deserializer.getObject();
			return object.isPresent() ? object.get() : deserializer.deserializeUnsafe();
		}
		catch (final JsonParseException | EventDataObjectDeserializationException e)
		{
			throw new StripeWebhookException(unreadable, false, e);
		}
	}

	private void recordFailure(final StripeWebhookEventModel event, final RuntimeException error)
	{
		final int attempts = attemptsOf(event) + 1;
		event.setAttempts(attempts);
		event.setLastError(summary(error));
		if (!isRetryable(error) || attempts >= MAX_ATTEMPTS)
		{
			event.setState(StripeWebhookEventState.FAILED);
			modelService.save(event);
			LOG.error("Stripe webhook {} ({}) failed after {} attempt(s): {}", event.getEventId(), event.getType(), attempts,
					event.getLastError());
			return;
		}
		modelService.save(event);
		final Duration delay = BACKOFF.get(attempts - 1);
		LOG.warn("Stripe webhook {} ({}) failed, attempt {} of {}; next attempt in {} s: {}", event.getEventId(),
				event.getType(), attempts, MAX_ATTEMPTS, delay.toSeconds(), event.getLastError());
		final RetryLaterException retry = new RetryLaterException("Stripe webhook " + event.getEventId() + " failed", error);
		retry.setMethod(RetryLaterException.Method.LINEAR);
		retry.setDelay(delay.toMillis());
		retry.setRollBack(false);
		throw retry;
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

	private static String summary(final Throwable error)
	{
		return StringUtils.abbreviate(error.getClass().getSimpleName() + ": " + StringUtils.defaultString(error.getMessage()),
				MAX_ERROR_LENGTH);
	}

	private static int attemptsOf(final StripeWebhookEventModel event)
	{
		return event.getAttempts() == null ? 0 : event.getAttempts();
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

	public void setStripePaymentIntentEventHandler(final StripePaymentIntentEventHandler stripePaymentIntentEventHandler)
	{
		this.stripePaymentIntentEventHandler = stripePaymentIntentEventHandler;
	}

	public void setStripeRefundEventHandler(final StripeRefundEventHandler stripeRefundEventHandler)
	{
		this.stripeRefundEventHandler = stripeRefundEventHandler;
	}
}
