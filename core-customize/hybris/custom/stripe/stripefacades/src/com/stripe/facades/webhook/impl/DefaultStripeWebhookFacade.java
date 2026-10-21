package com.stripe.facades.webhook.impl;

import com.google.gson.JsonSyntaxException;
import com.stripe.core.dao.StripeWebhookEventDao;
import com.stripe.core.enums.StripeWebhookEventState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripeWebhookEventModel;
import com.stripe.facades.webhook.StripeWebhookFacade;
import com.stripe.facades.webhook.StripeWebhookReceipt;
import com.stripe.model.Event;
import de.hybris.platform.servicelayer.exceptions.ModelSavingException;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskService;
import de.hybris.platform.tx.Transaction;
import de.hybris.platform.tx.TransactionBody;
import java.util.Date;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripeWebhookFacade}. The event is processed later by the task runner
 * {@value #RUNNER_BEAN}. The log never holds the payload or the signature.
 */
public class DefaultStripeWebhookFacade implements StripeWebhookFacade
{
	public static final String RUNNER_BEAN = "stripeWebhookEventTaskRunner";

	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeWebhookFacade.class);

	private StripeGateway stripeGateway;
	private StripeWebhookEventDao stripeWebhookEventDao;
	private ModelService modelService;
	private TaskService taskService;

	@Override
	public StripeWebhookReceipt receive(final String payload, final String signatureHeader)
	{
		if (StringUtils.isBlank(payload) || StringUtils.isBlank(signatureHeader))
		{
			LOG.warn("Stripe webhook rejected: payload or signature is missing");
			return StripeWebhookReceipt.INVALID_SIGNATURE;
		}
		final Event event;
		try
		{
			event = stripeGateway.constructEvent(payload, signatureHeader);
		}
		catch (final StripeGatewayException e)
		{
			if (e.isSignatureInvalid())
			{
				LOG.warn("Stripe webhook rejected: invalid signature");
				return StripeWebhookReceipt.INVALID_SIGNATURE;
			}
			throw e;
		}
		catch (final JsonSyntaxException e)
		{
			LOG.warn("Stripe webhook rejected: the signed payload is malformed");
			return StripeWebhookReceipt.INVALID_SIGNATURE;
		}

		if (StringUtils.isBlank(event.getId()))
		{
			LOG.warn("Stripe webhook rejected: the event has no id");
			return StripeWebhookReceipt.INVALID_SIGNATURE;
		}

		final Optional<StripeWebhookEventModel> existing = stripeWebhookEventDao.findByEventId(event.getId());
		if (existing.isPresent())
		{
			return duplicateOf(existing.get());
		}

		try
		{
			storeAndSchedule(event, payload);
		}
		catch (final ModelSavingException e)
		{
			final Optional<StripeWebhookEventModel> concurrent = stripeWebhookEventDao.findByEventId(event.getId());
			if (concurrent.isPresent())
			{
				return duplicateOf(concurrent.get());
			}
			throw e;
		}
		LOG.info("Stripe webhook {} ({}) accepted", event.getId(), event.getType());
		return StripeWebhookReceipt.ACCEPTED;
	}

	private StripeWebhookReceipt duplicateOf(final StripeWebhookEventModel existing)
	{
		if (StripeWebhookEventState.FAILED.equals(existing.getState()))
		{
			requeue(existing);
			return StripeWebhookReceipt.ACCEPTED;
		}
		if (StripeWebhookEventState.RECEIVED.equals(existing.getState()) && !stripeWebhookEventDao.hasScheduledTask(existing))
		{
			LOG.warn("Stripe webhook {} was stored without a task; scheduling it again", existing.getEventId());
			scheduleTask(existing);
		}
		LOG.debug("Stripe webhook {} is a duplicate", existing.getEventId());
		return StripeWebhookReceipt.DUPLICATE;
	}

	/**
	 * A FAILED event that Stripe sends again (a resend from the Stripe Dashboard or the Stripe CLI) is the recovery
	 * path: the event is RECEIVED again with no attempts and no error, and a new task processes it.
	 */
	private void requeue(final StripeWebhookEventModel failed)
	{
		LOG.warn("Stripe webhook {} ({}) failed before ({}); it is sent again and is processed again", failed.getEventId(),
				failed.getType(), failed.getLastError());
		executeInTransaction(() -> {
			failed.setState(StripeWebhookEventState.RECEIVED);
			failed.setAttempts(0);
			failed.setLastError(null);
			modelService.save(failed);
			scheduleTask(failed);
		});
	}

	private void storeAndSchedule(final Event event, final String payload)
	{
		executeInTransaction(() -> {
			final StripeWebhookEventModel stored = modelService.create(StripeWebhookEventModel.class);
			stored.setEventId(event.getId());
			stored.setType(event.getType());
			stored.setPayload(payload);
			stored.setState(StripeWebhookEventState.RECEIVED);
			modelService.save(stored);
			scheduleTask(stored);
		});
	}

	private void scheduleTask(final StripeWebhookEventModel stored)
	{
		final TaskModel task = modelService.create(TaskModel.class);
		task.setRunnerBean(RUNNER_BEAN);
		task.setContextItem(stored);
		task.setExecutionDate(new Date());
		taskService.scheduleTask(task);
	}

	/**
	 * Runs the body in the current database transaction: a failure rolls everything back. Tests override it.
	 */
	protected void executeInTransaction(final Runnable body)
	{
		try
		{
			Transaction.current().execute(new TransactionBody()
			{
				@Override
				public <T> T execute() throws Exception
				{
					body.run();
					return null;
				}
			});
		}
		catch (final RuntimeException e)
		{
			throw e;
		}
		catch (final Exception e)
		{
			throw new IllegalStateException("Stripe webhook intake failed", e);
		}
	}

	public void setStripeGateway(final StripeGateway stripeGateway)
	{
		this.stripeGateway = stripeGateway;
	}

	public void setStripeWebhookEventDao(final StripeWebhookEventDao stripeWebhookEventDao)
	{
		this.stripeWebhookEventDao = stripeWebhookEventDao;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public void setTaskService(final TaskService taskService)
	{
		this.taskService = taskService;
	}
}
