package com.stripe.core.order;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.site.StripeSiteContext;
import com.stripe.core.testsupport.StripeSiteContextMocks;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.core.model.product.ProductModel;
import de.hybris.platform.task.RetryLaterException;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskService;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class StripePaymentAdjustmentTaskRunnerTest
{
	private StripeSiteContext siteContext;
	private BaseSiteModel site;
	private StripePaymentAdjustmentService adjustmentService;
	private TaskService taskService;
	private StripePaymentAdjustmentTaskRunner runner;
	private OrderModel order;
	private TaskModel task;

	@Before
	public void setUp()
	{
		adjustmentService = mock(StripePaymentAdjustmentService.class);
		taskService = mock(TaskService.class);
		runner = new StripePaymentAdjustmentTaskRunner();
		runner.setStripePaymentAdjustmentService(adjustmentService);
		siteContext = StripeSiteContextMocks.direct();
		runner.setStripeSiteContext(siteContext);
		site = new BaseSiteModel();
		site.setUid("site-c");
		order = new OrderModel();
		order.setCode("order-retry");
		order.setSite(site);
		task = new TaskModel();
		task.setContextItem(order);
	}

	@Test
	public void adjustedOrderFinishesTheTask() throws Exception
	{
		when(adjustmentService.adjustToOrderTotal(order)).thenReturn(true);

		runner.run(taskService, task);

		verify(adjustmentService).adjustToOrderTotal(order);
	}

	@Test
	public void failedAdjustmentRetriesWithBackoff()
	{
		when(adjustmentService.adjustToOrderTotal(order)).thenReturn(false);
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
				assertEquals(RetryLaterException.Method.LINEAR, e.getMethod());
			}
		}
	}

	@Test
	public void gatewayFailureRetries()
	{
		when(adjustmentService.adjustToOrderTotal(order))
				.thenThrow(new StripeGatewayException("down", null, true, false, null));
		task.setRetry(1);

		try
		{
			runner.run(taskService, task);
			fail("expected RetryLaterException");
		}
		catch (final RetryLaterException e)
		{
			assertEquals(120_000L, e.getDelay());
		}
	}

	@Test
	public void fifthFailedAttemptGivesUpWithoutRetry() throws Exception
	{
		when(adjustmentService.adjustToOrderTotal(order)).thenReturn(false);
		task.setRetry(4);

		runner.run(taskService, task);

		verify(adjustmentService).adjustToOrderTotal(order);
	}

	@Test
	public void taskWithoutOrderDoesNothing() throws Exception
	{
		task.setContextItem(new ProductModel());

		runner.run(taskService, task);

		verifyNoInteractions(adjustmentService);
	}

	@Test
	public void adjustmentRunsInTheSiteOfTheOrder() throws Exception
	{
		when(adjustmentService.adjustToOrderTotal(order)).thenReturn(true);

		runner.run(taskService, task);

		verify(siteContext).runInSite(same(site), org.mockito.ArgumentMatchers.any());
		verify(adjustmentService).adjustToOrderTotal(order);
	}
}
