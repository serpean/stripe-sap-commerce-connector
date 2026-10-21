package com.stripe.core.order;

import com.stripe.core.payment.StripePaymentInfos;
import com.stripe.core.site.StripeSiteContext;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.task.RetryLaterException;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskRunner;
import de.hybris.platform.task.TaskService;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries the Stripe payment adjustment of an order (the context item of the task) after a cancel. The adjustment reads
 * the live PaymentIntent, so a retry never refunds twice. A failed attempt (refund or void not ACCEPTED, or an exception)
 * gives a {@link RetryLaterException} after 30 s, 2 min, 8 min and 30 min. The work of an attempt is kept (a REJECTED
 * refund entry is a real answer of Stripe). After the fifth failed attempt the runner logs an ERROR with the order and
 * the PaymentIntent and stops. The adjustment runs in the site of the order, so the Stripe settings of that site are
 * used.
 */
public class StripePaymentAdjustmentTaskRunner implements TaskRunner<TaskModel>
{
	static final int MAX_ATTEMPTS = 5;
	static final List<Duration> BACKOFF = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(8),
			Duration.ofMinutes(30));

	private static final Logger LOG = LoggerFactory.getLogger(StripePaymentAdjustmentTaskRunner.class);

	private StripePaymentAdjustmentService stripePaymentAdjustmentService;
	private StripeSiteContext stripeSiteContext;

	@Override
	public void run(final TaskService taskService, final TaskModel task) throws RetryLaterException
	{
		if (!(task.getContextItem() instanceof OrderModel order))
		{
			LOG.warn("Task {} has no order: no Stripe payment to adjust", task.getPk());
			return;
		}
		String failure;
		try
		{
			if (stripeSiteContext.runInSite(order.getSite(), () -> stripePaymentAdjustmentService.adjustToOrderTotal(order)))
			{
				LOG.info("Stripe payment of order {} is adjusted", order.getCode());
				return;
			}
			failure = "refund or void not accepted";
		}
		catch (final RuntimeException e)
		{
			failure = e.getMessage();
		}

		final int attempt = (task.getRetry() == null ? 0 : task.getRetry()) + 1;
		if (attempt >= MAX_ATTEMPTS)
		{
			LOG.error("Stripe payment of order {} (payment intent {}) is not adjusted after {} attempts: {}. "
					+ "No more retries; check the payment in Stripe", order.getCode(),
					StripePaymentInfos.paymentIntentId(order), attempt, failure);
			return;
		}
		final Duration delay = BACKOFF.get(attempt - 1);
		LOG.warn("Stripe payment adjustment of order {} failed, attempt {} of {}; next attempt in {} s: {}",
				order.getCode(), attempt, MAX_ATTEMPTS, delay.toSeconds(), failure);
		final RetryLaterException retry = new RetryLaterException(
				"Stripe payment adjustment of order " + order.getCode() + " failed");
		retry.setMethod(RetryLaterException.Method.LINEAR);
		retry.setDelay(delay.toMillis());
		retry.setRollBack(false);
		throw retry;
	}

	@Override
	public void handleError(final TaskService taskService, final TaskModel task, final Throwable error)
	{
		final Object item = task.getContextItem();
		final String orderCode = item instanceof OrderModel order ? order.getCode() : null;
		LOG.error("Stripe payment adjustment task of order {} failed: {}", orderCode,
				error == null ? null : error.getMessage());
	}

	public void setStripePaymentAdjustmentService(final StripePaymentAdjustmentService stripePaymentAdjustmentService)
	{
		this.stripePaymentAdjustmentService = stripePaymentAdjustmentService;
	}

	public void setStripeSiteContext(final StripeSiteContext stripeSiteContext)
	{
		this.stripeSiteContext = stripeSiteContext;
	}
}
