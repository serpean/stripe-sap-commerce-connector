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
import de.hybris.platform.task.TaskService;
import de.hybris.platform.task.TaskModel;
import java.util.Date;
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

		if (stripeWebhookEventDao.findByEventId(event.getId()).isPresent())
		{
			LOG.debug("Stripe webhook {} is a duplicate", event.getId());
			return StripeWebhookReceipt.DUPLICATE;
		}

		final StripeWebhookEventModel stored = modelService.create(StripeWebhookEventModel.class);
		stored.setEventId(event.getId());
		stored.setType(event.getType());
		stored.setPayload(payload);
		stored.setState(StripeWebhookEventState.RECEIVED);
		try
		{
			modelService.save(stored);
		}
		catch (final ModelSavingException e)
		{
			if (stripeWebhookEventDao.findByEventId(event.getId()).isPresent())
			{
				LOG.debug("Stripe webhook {} was stored by a concurrent call", event.getId());
				return StripeWebhookReceipt.DUPLICATE;
			}
			throw e;
		}

		final TaskModel task = modelService.create(TaskModel.class);
		task.setRunnerBean(RUNNER_BEAN);
		task.setContextItem(stored);
		task.setExecutionDate(new Date());
		taskService.scheduleTask(task);
		LOG.info("Stripe webhook {} ({}) accepted", event.getId(), event.getType());
		return StripeWebhookReceipt.ACCEPTED;
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
