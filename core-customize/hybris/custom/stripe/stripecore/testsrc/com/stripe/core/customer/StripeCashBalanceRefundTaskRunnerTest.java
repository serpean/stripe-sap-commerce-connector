package com.stripe.core.customer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.stripe.core.gateway.StripeGatewayException;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.task.RetryLaterException;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskService;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripeCashBalanceRefundTaskRunnerTest
{
	private StripeCashBalanceService service;
	private StripeCashBalanceRefundTaskRunner runner;
	private TaskModel task;
	private final TaskService taskService = mock(TaskService.class);

	@Before
	public void setUp()
	{
		service = mock(StripeCashBalanceService.class);
		runner = new StripeCashBalanceRefundTaskRunner();
		runner.setStripeCashBalanceService(service);
		task = new TaskModel();
		task.setContext("cash-refund-check:cus_1:eur");
	}

	@Test
	public void refundsTheBalanceOfTheContext() throws Exception
	{
		runner.run(taskService, task);

		verify(service).refundUnappliedBalance("cus_1", "eur");
	}

	@Test
	public void nonRetryableStripeErrorEndsAtOnce() throws Exception
	{
		doThrow(new StripeGatewayException("x", "email_invalid", false, false, null)).when(service)
				.refundUnappliedBalance("cus_1", "eur");

		runner.run(taskService, task);

		verify(service).refundUnappliedBalance("cus_1", "eur");
	}

	@Test
	public void failureRetriesWithBackoffAndStopsAfterFiveAttempts()
	{
		doThrow(new StripeGatewayException("x", "api_error", true, false, null)).when(service)
				.refundUnappliedBalance("cus_1", "eur");
		final long[] expected = { 30_000L, 120_000L, 480_000L, 1_800_000L };
		for (int retry = 0; retry < expected.length; retry++)
		{
			task.setRetry(retry);
			try
			{
				runner.run(taskService, task);
				fail("expected RetryLaterException");
			}
			catch (final RetryLaterException e)
			{
				assertEquals(expected[retry], e.getDelay());
			}
		}
		task.setRetry(4);
		try
		{
			runner.run(taskService, task);
		}
		catch (final RetryLaterException e)
		{
			fail("fifth attempt must end the task");
		}
	}
}
