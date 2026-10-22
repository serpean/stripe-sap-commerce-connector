package com.stripe.core.customer;

import com.stripe.core.customer.impl.DefaultStripeCashBalanceService;
import de.hybris.platform.task.RetryLaterException;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskRunner;
import de.hybris.platform.task.TaskService;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks the cash balance of one Stripe customer and currency (the context of the task, text
 * {@code cash-refund-check:<customer>:<currency>}) and refunds what no open bank transfer can use. A Stripe error gives
 * a {@link RetryLaterException} after 30 s, 2 min, 8 min and 30 min. After the fifth failed attempt the runner logs an
 * ERROR and stops. The refund key holds the balance, so a retry never refunds twice.
 */
public class StripeCashBalanceRefundTaskRunner implements TaskRunner<TaskModel>
{
	static final int MAX_ATTEMPTS = 5;
	static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(8),
			Duration.ofMinutes(30));

	private static final Logger LOG = LoggerFactory.getLogger(StripeCashBalanceRefundTaskRunner.class);

	private StripeCashBalanceService stripeCashBalanceService;

	@Override
	public void run(final TaskService taskService, final TaskModel task) throws RetryLaterException
	{
		final String[] parts = partsOf(task);
		if (parts == null)
		{
			LOG.warn("Task {} has no cash balance check: nothing to do", task.getPk());
			return;
		}
		try
		{
			stripeCashBalanceService.refundUnappliedBalance(parts[0], parts[1]);
		}
		catch (final RuntimeException e)
		{
			final int attempt = (task.getRetry() == null ? 0 : task.getRetry()) + 1;
			if (attempt >= MAX_ATTEMPTS)
			{
				LOG.error("Cash balance of Stripe customer {} ({}) is not refunded after {} attempts: {}. "
						+ "No more retries; check the customer in Stripe", parts[0], parts[1], attempt, e.getMessage());
				return;
			}
			final Duration delay = BACKOFF.get(attempt - 1);
			LOG.warn("Cash balance refund of Stripe customer {} ({}) failed, attempt {} of {}; next attempt in {} s: {}",
					parts[0], parts[1], attempt, MAX_ATTEMPTS, delay.toSeconds(), e.getMessage());
			final RetryLaterException retry = new RetryLaterException("Cash balance refund of " + parts[0] + " failed", e);
			retry.setMethod(RetryLaterException.Method.LINEAR);
			retry.setDelay(delay.toMillis());
			retry.setRollBack(false);
			throw retry;
		}
	}

	@Override
	public void handleError(final TaskService taskService, final TaskModel task, final Throwable error)
	{
		LOG.error("Cash balance refund task {} failed: {}", task.getContext(), error == null ? null : error.getMessage());
	}

	private static String[] partsOf(final TaskModel task)
	{
		final Object context = task.getContext();
		final String prefix = DefaultStripeCashBalanceService.CONTEXT_PREFIX;
		if (!(context instanceof String text) || !text.startsWith(prefix))
		{
			return null;
		}
		final String[] parts = text.substring(prefix.length()).split(":");
		return parts.length == 2 ? parts : null;
	}

	public void setStripeCashBalanceService(final StripeCashBalanceService stripeCashBalanceService)
	{
		this.stripeCashBalanceService = stripeCashBalanceService;
	}
}
