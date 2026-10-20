package com.stripe.core.process;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.payment.StripePaymentInfos;
import com.stripe.core.webhook.StripeRowLocks;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.RetryLaterException;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the first action of the order process ({@code checkOrderAction}). A Stripe order whose payment is pending
 * (no ACCEPTED authorization of its PaymentIntent) waits here:
 * <ul>
 * <li>payment accepted: the wrapped action runs;</li>
 * <li>payment failed, canceled or refunded, or still pending after {@code stripe.payment.pending.timeout.seconds}: the
 * order is cancelled and the action ends with NOK;</li>
 * <li>payment pending: the order gets the status PAYMENT_PENDING and the action runs again after
 * {@code stripe.payment.pending.retry.seconds}.</li>
 * </ul>
 * The record row of the PaymentIntent is locked first, so the action and the webhook handler do not decide at the same
 * time. Orders not paid with Stripe go to the wrapped action.
 */
public class StripeAwaitPaymentCheckOrderAction extends AbstractSimpleDecisionAction<OrderProcessModel>
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeAwaitPaymentCheckOrderAction.class);

	private static final Set<StripePaymentIntentState> PAYMENT_FAILED = Set.of(StripePaymentIntentState.FAILED,
			StripePaymentIntentState.CANCELED, StripePaymentIntentState.REFUNDED);

	private final AbstractSimpleDecisionAction<OrderProcessModel> delegate;
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private StripeOrderAuthorizationService stripeOrderAuthorizationService;
	private StripeOrderCancellationService stripeOrderCancellationService;
	private StripeConfigurationService stripeConfigurationService;
	private TimeService timeService;

	public StripeAwaitPaymentCheckOrderAction(final AbstractSimpleDecisionAction<OrderProcessModel> delegate)
	{
		this.delegate = delegate;
	}

	@Override
	public Transition executeAction(final OrderProcessModel process) throws RetryLaterException, Exception
	{
		final OrderModel order = process.getOrder();
		final String paymentIntentId = StripePaymentInfos.paymentIntentId(order);
		if (paymentIntentId == null)
		{
			return delegate.executeAction(process);
		}

		final Optional<StripePaymentIntentRecordModel> record = stripePaymentIntentRecordDao
				.findByPaymentIntentId(paymentIntentId);
		record.ifPresent(found -> StripeRowLocks.lockAndRefresh(getModelService(), found));
		getModelService().refresh(order);

		if (OrderStatus.CANCELLED.equals(order.getStatus()))
		{
			LOG.info("Order {} is cancelled: the order process ends", order.getCode());
			return Transition.NOK;
		}
		if (stripeOrderAuthorizationService.hasAcceptedAuthorization(order, paymentIntentId))
		{
			return delegate.executeAction(process);
		}

		final StripePaymentIntentState state = record.map(StripePaymentIntentRecordModel::getState).orElse(null);
		if (state != null && PAYMENT_FAILED.contains(state))
		{
			LOG.info("Payment intent {} of order {} has state {}: cancelling the order", paymentIntentId, order.getCode(),
					state);
			stripeOrderCancellationService.cancelForFailedPayment(order);
			return Transition.NOK;
		}
		final Instant now = timeService.getCurrentTime().toInstant();
		final Instant deadline = placedAt(order).plus(stripeConfigurationService.getPendingPaymentTimeout());
		if (!now.isBefore(deadline))
		{
			LOG.warn("Payment intent {} of order {} is still pending after {}: cancelling the order", paymentIntentId,
					order.getCode(), stripeConfigurationService.getPendingPaymentTimeout());
			stripeOrderCancellationService.cancelForFailedPayment(order);
			return Transition.NOK;
		}

		if (!OrderStatus.PAYMENT_PENDING.equals(order.getStatus()))
		{
			setOrderStatus(order, OrderStatus.PAYMENT_PENDING);
		}
		LOG.info("Payment intent {} of order {} is pending: checking again later", paymentIntentId, order.getCode());
		final RetryLaterException retry = new RetryLaterException("Payment of order " + order.getCode() + " is pending");
		retry.setMethod(RetryLaterException.Method.LINEAR);
		retry.setDelay(stripeConfigurationService.getPendingPaymentRetryDelay().toMillis());
		retry.setRollBack(false);
		throw retry;
	}

	/**
	 * The order date, or the creation time when the order has no date. An order without both is treated as placed long
	 * ago, so its pending payment is not waited for.
	 */
	private static Instant placedAt(final OrderModel order)
	{
		final Date placed = order.getDate() != null ? order.getDate() : order.getCreationtime();
		return placed == null ? Instant.EPOCH : placed.toInstant();
	}

	public void setStripePaymentIntentRecordDao(final StripePaymentIntentRecordDao stripePaymentIntentRecordDao)
	{
		this.stripePaymentIntentRecordDao = stripePaymentIntentRecordDao;
	}

	public void setStripeOrderAuthorizationService(final StripeOrderAuthorizationService stripeOrderAuthorizationService)
	{
		this.stripeOrderAuthorizationService = stripeOrderAuthorizationService;
	}

	public void setStripeOrderCancellationService(final StripeOrderCancellationService stripeOrderCancellationService)
	{
		this.stripeOrderCancellationService = stripeOrderCancellationService;
	}

	public void setStripeConfigurationService(final StripeConfigurationService stripeConfigurationService)
	{
		this.stripeConfigurationService = stripeConfigurationService;
	}

	public void setTimeService(final TimeService timeService)
	{
		this.timeService = timeService;
	}
}
