package com.stripe.facades.webhook.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskService;
import java.util.Date;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

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
	private boolean transactionOpen;

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
		facade = new DefaultStripeWebhookFacade()
		{
			@Override
			protected void executeInTransaction(final Runnable body)
			{
				transactionOpen = true;
				try
				{
					body.run();
				}
				finally
				{
					transactionOpen = false;
				}
			}
		};
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

	@Test
	public void eventWithoutIdIsRejected()
	{
		final Event noId = new Event();
		noId.setType("payment_intent.succeeded");
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(noId);

		assertEquals(StripeWebhookReceipt.INVALID_SIGNATURE, facade.receive(PAYLOAD, SIGNATURE));
		verify(dao, never()).findByEventId(any());
		verify(modelService, never()).save(any());
		verify(taskService, never()).scheduleTask(any());
	}

	@Test
	public void saveAndScheduleRunInsideTheTransaction()
	{
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(event());
		doAnswer(invocation -> {
			assertTrue(transactionOpen);
			return null;
		}).when(modelService).save(stored);
		doAnswer(invocation -> {
			assertTrue(transactionOpen);
			return null;
		}).when(taskService).scheduleTask(task);

		assertEquals(StripeWebhookReceipt.ACCEPTED, facade.receive(PAYLOAD, SIGNATURE));
		verify(taskService).scheduleTask(task);
	}

	@Test
	public void schedulingFailureLeavesNoOrphanEvent()
	{
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(event());
		doThrow(new IllegalStateException("scheduler down")).when(taskService).scheduleTask(task);

		try
		{
			facade.receive(PAYLOAD, SIGNATURE);
			fail("the failure must reach the caller");
		}
		catch (final IllegalStateException e)
		{
			assertEquals("scheduler down", e.getMessage());
		}
		assertFalse("the failure must leave the transaction", transactionOpen);
	}

	@Test
	public void duplicateOfOrphanReceivedEventIsRescheduled()
	{
		final StripeWebhookEventModel orphan = mock(StripeWebhookEventModel.class);
		when(orphan.getState()).thenReturn(StripeWebhookEventState.RECEIVED);
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(event());
		when(dao.findByEventId("evt_1")).thenReturn(Optional.of(orphan));
		when(dao.hasScheduledTask(orphan)).thenReturn(false);

		assertEquals(StripeWebhookReceipt.DUPLICATE, facade.receive(PAYLOAD, SIGNATURE));
		verify(task).setContextItem(orphan);
		verify(taskService).scheduleTask(task);
		verify(modelService, never()).save(any());
	}

	@Test
	public void duplicateOfScheduledEventIsNotRescheduled()
	{
		final StripeWebhookEventModel scheduled = mock(StripeWebhookEventModel.class);
		when(scheduled.getState()).thenReturn(StripeWebhookEventState.RECEIVED);
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(event());
		when(dao.findByEventId("evt_1")).thenReturn(Optional.of(scheduled));
		when(dao.hasScheduledTask(scheduled)).thenReturn(true);

		assertEquals(StripeWebhookReceipt.DUPLICATE, facade.receive(PAYLOAD, SIGNATURE));
		verify(taskService, never()).scheduleTask(any());
	}

	@Test
	public void duplicateOfProcessedEventIsNotRescheduled()
	{
		final StripeWebhookEventModel processed = mock(StripeWebhookEventModel.class);
		when(processed.getState()).thenReturn(StripeWebhookEventState.PROCESSED);
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(event());
		when(dao.findByEventId("evt_1")).thenReturn(Optional.of(processed));

		assertEquals(StripeWebhookReceipt.DUPLICATE, facade.receive(PAYLOAD, SIGNATURE));
		verify(dao, never()).hasScheduledTask(any());
		verify(taskService, never()).scheduleTask(any());
	}

	@Test
	public void resentFailedEventIsQueuedAgain()
	{
		final StripeWebhookEventModel failed = new StripeWebhookEventModel();
		failed.setEventId("evt_1");
		failed.setState(StripeWebhookEventState.FAILED);
		failed.setAttempts(5);
		failed.setLastError("StripeGatewayException: Stripe is not reachable");
		when(gateway.constructEvent(PAYLOAD, SIGNATURE)).thenReturn(event());
		when(dao.findByEventId("evt_1")).thenReturn(Optional.of(failed));
		doAnswer(invocation -> {
			assertTrue("the event and its task are saved in one transaction", transactionOpen);
			return null;
		}).when(taskService).scheduleTask(task);

		assertEquals(StripeWebhookReceipt.ACCEPTED, facade.receive(PAYLOAD, SIGNATURE));

		assertEquals(StripeWebhookEventState.RECEIVED, failed.getState());
		assertEquals(Integer.valueOf(0), failed.getAttempts());
		assertNull(failed.getLastError());
		final InOrder inOrder = inOrder(modelService, taskService);
		inOrder.verify(modelService).save(failed);
		inOrder.verify(taskService).scheduleTask(task);
		verify(task).setContextItem(failed);
	}
}
