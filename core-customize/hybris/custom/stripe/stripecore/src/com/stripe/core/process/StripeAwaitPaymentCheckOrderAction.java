package com.stripe.core.process;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.payment.StripePaymentInfos;
import com.stripe.core.payment.StripePaymentIntentChecks;
import com.stripe.core.site.StripeSiteContext;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.core.webhook.StripeRowLocks;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.RetryLaterException;
import java.time.Instant;
import java.util.Date;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the first action of the order process ({@code checkOrderAction}). A Stripe order whose payment is pending
 * (no ACCEPTED authorization of its PaymentIntent) waits here. Each check reads the PaymentIntent from Stripe, so a
 * lost or failed webhook event does not decide:
 * <ul>
 * <li>payment authorized or captured with all its money: the order is authorized and the wrapped action runs; a
 * rejected authorization cancels the order and gives the money back;</li>
 * <li>payment failed, canceled or refunded (also when this connector released the funds), or still pending after
 * {@code stripe.payment.pending.timeout.seconds}: the order is cancelled and the action ends with NOK;</li>
 * <li>payment pending, or Stripe does not answer: the order gets the status PAYMENT_PENDING and the action runs again
 * after {@code stripe.payment.pending.retry.seconds}. Without an answer of Stripe the order is never cancelled.</li>
 * </ul>
 * The record row of the PaymentIntent is locked first, so the action and the webhook handler do not decide at the same
 * time. The decision runs in the site of the order, so the Stripe settings of that site are used. Orders not paid with
 * Stripe go to the wrapped action.
 */
public class StripeAwaitPaymentCheckOrderAction extends AbstractSimpleDecisionAction<OrderProcessModel>
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeAwaitPaymentCheckOrderAction.class);

	private static final Set<StripePaymentIntentState> FUNDS_RELEASED = Set.of(StripePaymentIntentState.CANCELED,
			StripePaymentIntentState.REFUNDED);
	private static final Set<StripePaymentIntentStatus> PAID = EnumSet.of(StripePaymentIntentStatus.AUTHORIZED,
			StripePaymentIntentStatus.CAPTURED);
	private static final Set<StripePaymentIntentStatus> DEAD = EnumSet.of(StripePaymentIntentStatus.FAILED,
			StripePaymentIntentStatus.CANCELED);

	private final AbstractSimpleDecisionAction<OrderProcessModel> delegate;
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private StripeOrderAuthorizationService stripeOrderAuthorizationService;
	private StripeOrderCancellationService stripeOrderCancellationService;
	private StripeConfigurationService stripeConfigurationService;
	private StripeSiteContext stripeSiteContext;
	private StripeGateway stripeGateway;
	private StripePaymentIntentChecks stripePaymentIntentChecks;
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
		return stripeSiteContext.runInSite(order.getSite(), () -> decide(process, order, paymentIntentId));
	}

	private Transition decide(final OrderProcessModel process, final OrderModel order, final String paymentIntentId)
			throws Exception
	{
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
		if (state != null && FUNDS_RELEASED.contains(state))
		{
			LOG.info("Funds of payment intent {} of order {} were given back (record state {}): cancelling the order",
					paymentIntentId, order.getCode(), state);
			return cancel(order);
		}

		final Optional<PaymentIntent> live = livePaymentIntent(paymentIntentId);
		final StripePaymentIntentStatus status = live.map(StripeAwaitPaymentCheckOrderAction::statusOf).orElse(null);
		if (status != null && PAID.contains(status))
		{
			return onPaid(process, order, live.get());
		}
		if (status != null && DEAD.contains(status))
		{
			LOG.info("Payment intent {} of order {} has status {}: cancelling the order", paymentIntentId, order.getCode(),
					status);
			return cancel(order);
		}

		final Instant now = timeService.getCurrentTime().toInstant();
		final Instant deadline = placedAt(order).plus(stripeConfigurationService.getPendingPaymentTimeout());
		if (!now.isBefore(deadline))
		{
			if (status != null)
			{
				LOG.warn("Payment intent {} of order {} is still {} after {}: cancelling the order", paymentIntentId,
						order.getCode(), status, stripeConfigurationService.getPendingPaymentTimeout());
				return cancel(order);
			}
			LOG.error("Payment of order {} is pending after {} but payment intent {} cannot be read: checking again later "
					+ "instead of cancelling", order.getCode(), stripeConfigurationService.getPendingPaymentTimeout(),
					paymentIntentId);
		}
		return waitForPayment(order, paymentIntentId);
	}

	/**
	 * A paid PaymentIntent authorizes the order when it still holds all its money. A refunded payment, or a rejected
	 * authorization (for example, the amounts differ), cancels the order; the cancellation gives the money back. An
	 * authorization that did not finish (Stripe not reachable) is checked again later.
	 */
	private Transition onPaid(final OrderProcessModel process, final OrderModel order, final PaymentIntent paymentIntent)
			throws Exception
	{
		final String paymentIntentId = paymentIntent.getId();
		if (!stripePaymentIntentChecks.holdsAllMoney(paymentIntent))
		{
			LOG.warn("Payment intent {} of order {} is captured but part of its money was given back: cancelling the order",
					paymentIntentId, order.getCode());
			return cancel(order);
		}
		final boolean authorized;
		try
		{
			authorized = stripeOrderAuthorizationService.authorizePendingOrder(order, paymentIntent);
		}
		catch (final IllegalStateException e)
		{
			LOG.warn("Authorization of order {} by payment intent {} did not finish: checking again later ({})",
					order.getCode(), paymentIntentId, e.getMessage());
			return waitForPayment(order, paymentIntentId);
		}
		if (!authorized)
		{
			LOG.error("Authorization of order {} by payment intent {} was rejected: cancelling the order and releasing the "
					+ "funds", order.getCode(), paymentIntentId);
			return cancel(order);
		}
		LOG.info("Payment intent {} of pending order {} is paid: the order process goes on", paymentIntentId,
				order.getCode());
		return delegate.executeAction(process);
	}

	private Transition cancel(final OrderModel order)
	{
		stripeOrderCancellationService.cancelForFailedPayment(order);
		return Transition.NOK;
	}

	private Transition waitForPayment(final OrderModel order, final String paymentIntentId) throws RetryLaterException
	{
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
	 * The live PaymentIntent, or empty when Stripe does not answer or sends a status this connector does not know.
	 */
	private Optional<PaymentIntent> livePaymentIntent(final String paymentIntentId)
	{
		try
		{
			final PaymentIntent paymentIntent = stripeGateway.retrievePaymentIntent(paymentIntentId);
			return paymentIntent == null || statusOf(paymentIntent) == null ? Optional.empty() : Optional.of(paymentIntent);
		}
		catch (final RuntimeException e)
		{
			LOG.warn("Payment intent {} cannot be read: {}", paymentIntentId, e.getMessage());
			return Optional.empty();
		}
	}

	private static StripePaymentIntentStatus statusOf(final PaymentIntent paymentIntent)
	{
		try
		{
			return StripePaymentIntentStatus.of(paymentIntent.getStatus());
		}
		catch (final IllegalArgumentException e)
		{
			return null;
		}
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

	public void setStripeSiteContext(final StripeSiteContext stripeSiteContext)
	{
		this.stripeSiteContext = stripeSiteContext;
	}

	public void setStripeGateway(final StripeGateway stripeGateway)
	{
		this.stripeGateway = stripeGateway;
	}

	public void setStripePaymentIntentChecks(final StripePaymentIntentChecks stripePaymentIntentChecks)
	{
		this.stripePaymentIntentChecks = stripePaymentIntentChecks;
	}
}
