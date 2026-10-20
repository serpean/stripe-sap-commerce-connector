package com.stripe.facades.webhook.impl;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonSyntaxException;
import com.stripe.core.dao.StripeWebhookEventDao;
import com.stripe.core.enums.StripeWebhookEventState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripeWebhookEventModel;
import com.stripe.facades.webhook.StripeWebhookReceipt;
import com.stripe.model.Event;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.servicelayer.exceptions.ModelSavingException;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.task.TaskService;
import de.hybris.platform.task.TaskModel;
import java.util.Date;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class DefaultStripeWebhookFacadeTest
{
	private static final String PAYLOAD = "{\"id\":\"evt_1\"}";
	private static final String SIGNATURE = "t=1,v1=abc";

	private StripeGateway gateway;
	private StripeWebhookEventDao dao;
	private ModelService modelService;
	private TaskService taskService;
	private DefaultStripeWebhookFacade facade;
	private StripeWebhookEventModel stored;
	private TaskModel task;

	@Before
	public void setUp()
	{
		gateway = mock(StripeGateway.class);
		dao = mock(StripeWebhookEventDao.class);
		modelService = mock(ModelService.class);
		taskService = mock(TaskService.class);
		stored = new StripeWebhookEventModel();
		task = mock(TaskModel.class);
		when(modelService.create(StripeWebhookEventModel.class)).thenReturn(stored);
		when(modelService.create(TaskModel.class)).thenReturn(task);
		when(dao.findByEventId("evt_1")).thenReturn(Optional.empty());
		facade = new DefaultStripeWebhookFacade();
		facade.setStripeGateway(gateway);
		facade.setStripeWebhookEventDao(dao);
		facade.setModelService(modelService);
		facade.setTaskService(taskService);
	}

	private Event event()
	{
		final Event event = new Event();
		event.setId("evt_1");
		event.setType("payment_intent.succeeded");
		return event;
	}

	@Test
	public void invalidSignatureIsRejected()
	{
		when(gateway.constructEvent(PAYLOAD, SIGNATURE))
				.thenThrow(new StripeGatewayException("bad signature", null, false, true, null));

		assertEquals(StripeWebhookReceipt.INVALID_SIGNATURE, facade.receive(PAYLOAD, SIGNATURE));
		verify(modelService, never()).save(any());
		verify(taskService, never()).scheduleTask(any());
	}

	@Test
	public void missingSignatureIsRejected()
	{
		assertEquals(StripeWebhookReceipt.INVALID_SIGNATURE, facade.receive(PAYLOAD, null));
		verify(taskService, never()).scheduleTask(any());
	}

	@Test
	public void malformedSignedPayloadIsRejected()
	{
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenThrow(new JsonSyntaxException("bad json"));

		assertEquals(StripeWebhookReceipt.INVALID_SIGNATURE, facade.receive(PAYLOAD, SIGNATURE));
		verify(taskService, never()).scheduleTask(any());
	}

	@Test(expected = StripeGatewayException.class)
	public void missingSecretIsNotReportedAsInvalidSignature()
	{
		when(gateway.constructEvent(PAYLOAD, SIGNATURE))
				.thenThrow(new StripeGatewayException("no secret", null, false, false, null));

		facade.receive(PAYLOAD, SIGNATURE);
	}

	@Test
	public void duplicateEventIsNotScheduledTwice()
	{
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(event());
		when(dao.findByEventId("evt_1")).thenReturn(Optional.of(new StripeWebhookEventModel()));

		assertEquals(StripeWebhookReceipt.DUPLICATE, facade.receive(PAYLOAD, SIGNATURE));
		verify(modelService, never()).save(any());
		verify(taskService, never()).scheduleTask(any());
	}

	@Test
	public void concurrentInsertIsReportedAsDuplicate()
	{
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(event());
		when(dao.findByEventId("evt_1")).thenReturn(Optional.empty(), Optional.of(new StripeWebhookEventModel()));
		doThrow(new ModelSavingException("unique index")).when(modelService).save(stored);

		assertEquals(StripeWebhookReceipt.DUPLICATE, facade.receive(PAYLOAD, SIGNATURE));
		verify(taskService, never()).scheduleTask(any());
	}

	@Test(expected = ModelSavingException.class)
	public void saveFailureWithoutExistingEventIsNotHidden()
	{
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(event());
		doThrow(new ModelSavingException("db down")).when(modelService).save(stored);

		facade.receive(PAYLOAD, SIGNATURE);
	}

	@Test
	public void newEventIsStoredAndScheduled()
	{
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(event());

		assertEquals(StripeWebhookReceipt.ACCEPTED, facade.receive(PAYLOAD, SIGNATURE));

		assertEquals("evt_1", stored.getEventId());
		assertEquals("payment_intent.succeeded", stored.getType());
		assertEquals(PAYLOAD, stored.getPayload());
		assertEquals(StripeWebhookEventState.RECEIVED, stored.getState());
		verify(modelService).save(stored);
		verify(task).setRunnerBean("stripeWebhookEventTaskRunner");
		verify(task).setContextItem(stored);
		verify(task).setExecutionDate(any(Date.class));
		verify(taskService).scheduleTask(task);
	}

	@Test
	public void acceptsEventWithDifferentApiVersion()
	{
		final Event other = event();
		other.setApiVersion("2020-08-27");
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(other);

		assertEquals(StripeWebhookReceipt.ACCEPTED, facade.receive(PAYLOAD, SIGNATURE));
		verify(modelService).save(stored);
		verify(taskService).scheduleTask(task);
	}
}
