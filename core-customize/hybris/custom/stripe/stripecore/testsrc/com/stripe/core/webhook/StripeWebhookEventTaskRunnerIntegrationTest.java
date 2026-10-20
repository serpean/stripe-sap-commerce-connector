package com.stripe.core.webhook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.stripe.Stripe;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripeWebhookEventState;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.model.StripeWebhookEventModel;
import de.hybris.bootstrap.annotations.IntegrationTest;
import de.hybris.platform.servicelayer.ServicelayerTest;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.RetryLaterException;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskService;
import de.hybris.platform.task.impl.TaskExecutionStrategy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.annotation.Resource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs the runner through the execution strategy of the task engine ({@code defaultTaskExecutionStrategy}), with real
 * database transactions. The test does the bookkeeping of {@code DefaultTaskService} after each
 * {@link RetryLaterException} (next date, {@code retry + 1}), so it does not depend on the engine polling in the junit
 * tenant. The task has an execution date far in the future, so a running engine does not take it.
 */
@IntegrationTest
public class StripeWebhookEventTaskRunnerIntegrationTest extends ServicelayerTest
{
	@Resource
	private ModelService modelService;
	@Resource
	private TaskService taskService;
	@Resource
	private TimeService timeService;
	@Resource
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	@Resource(name = "defaultTaskExecutionStrategy")
	private TaskExecutionStrategy taskExecutionStrategy;

	private final List<String> writtenByFailedAttempts = new ArrayList<>();
	private StripeWebhookEventModel event;
	private TaskModel task;

	@Before
	public void setUp()
	{
		event = modelService.create(StripeWebhookEventModel.class);
		event.setEventId("evt_engine_" + System.nanoTime());
		event.setType("payment_intent.succeeded");
		event.setState(StripeWebhookEventState.RECEIVED);
		event.setPayload("{\"id\":\"" + event.getEventId() + "\",\"object\":\"event\",\"api_version\":\""
				+ Stripe.API_VERSION + "\",\"created\":" + Instant.now().getEpochSecond()
				+ ",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_engine\","
				+ "\"object\":\"payment_intent\",\"status\":\"succeeded\"}}}");
		modelService.save(event);

		task = modelService.create(TaskModel.class);
		task.setRunnerBean("stripeWebhookEventTaskRunner");
		task.setContextItem(event);
		task.setExecutionDate(Date.from(Instant.now().plus(Duration.ofDays(365))));
		modelService.save(task);
	}

	@After
	public void tearDown()
	{
		modelService.removeAll(List.of(task, event));
	}

	@Test
	public void retryableFailureRetriesAndFailsAfterFifthAttemptWithoutKeepingPartialWork() throws Exception
	{
		final StripeWebhookEventTaskRunner runner = runnerWithFailingHandler();

		int retries = 0;
		while (true)
		{
			try
			{
				taskExecutionStrategy.run(taskService, runner, task);
				break;
			}
			catch (final RetryLaterException e)
			{
				final int retry = task.getRetry() == null ? 0 : task.getRetry();
				task.setExecutionDate(taskExecutionStrategy.handleRetry(taskService, runner, task, e, retry));
				task.setRetry(retry + 1);
				modelService.save(task);
				retries++;
			}
			catch (final RuntimeException e)
			{
				taskExecutionStrategy.handleError(taskService, runner, task, e);
				break;
			}
		}

		modelService.refresh(event);
		assertEquals(4, retries);
		assertEquals(5, writtenByFailedAttempts.size());
		assertEquals(StripeWebhookEventState.FAILED, event.getState());
		assertEquals(Integer.valueOf(5), event.getAttempts());
		assertTrue(event.getLastError().contains("Stripe is not reachable"));
		for (final String paymentIntentId : writtenByFailedAttempts)
		{
			assertFalse(stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntentId).isPresent());
		}
	}

	private StripeWebhookEventTaskRunner runnerWithFailingHandler()
	{
		final StripeWebhookEventTaskRunner runner = new StripeWebhookEventTaskRunner();
		runner.setModelService(modelService);
		runner.setTimeService(timeService);
		runner.setStripeRefundEventHandler(object -> StripeWebhookOutcome.IGNORED);
		runner.setStripePaymentIntentEventHandler((type, paymentIntent, created) -> {
			final StripePaymentIntentRecordModel partial = modelService.create(StripePaymentIntentRecordModel.class);
			partial.setPaymentIntentId("pi_partial_" + System.nanoTime());
			modelService.save(partial);
			writtenByFailedAttempts.add(partial.getPaymentIntentId());
			throw new StripeGatewayException("Stripe is not reachable", "api_connection_error", true, false, null);
		});
		return runner;
	}
}
