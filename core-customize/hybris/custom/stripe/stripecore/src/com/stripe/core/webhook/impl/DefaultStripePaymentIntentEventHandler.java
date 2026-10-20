package com.stripe.core.webhook.impl;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderCancellationService;
import com.stripe.core.order.StripeOrderPlacementService;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.core.webhook.StripePaymentIntentEventHandler;
import com.stripe.core.webhook.StripeWebhookException;
import com.stripe.core.webhook.StripeWebhookOutcome;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.RetryLaterException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripePaymentIntentEventHandler}. It acts on the current state of the PaymentIntent at Stripe, not on
 * the state in the event, so a late or repeated event does no harm.
 * <ul>
 * <li>Confirmation events (succeeded, amount_capturable_updated, processing): authorizes the pending order, or, when
 * there is no order after the grace period, places it from the server. When the order was cancelled before the
 * payment succeeded, the funds are released.</li>
 * <li>Failure events (payment_failed, canceled): cancels the pending order.</li>
 * </ul>
 * An event for a PaymentIntent without a record (not created by this shop) is ignored.
 * <p>
 * {@link StripeOrderPlacementService} and {@link StripeOrderCancellationService} are optional properties: when one is
 * not configured, an event that needs it fails with a clear error.
 */
public class DefaultStripePaymentIntentEventHandler implements StripePaymentIntentEventHandler
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripePaymentIntentEventHandler.class);

	private static final Set<String> CONFIRMATION_EVENTS = Set.of("payment_intent.succeeded",
			"payment_intent.amount_capturable_updated", "payment_intent.processing");
	private static final Set<String> FAILURE_EVENTS = Set.of("payment_intent.payment_failed", "payment_intent.canceled");
	private static final Set<StripePaymentIntentStatus> PAID = EnumSet.of(StripePaymentIntentStatus.AUTHORIZED,
			StripePaymentIntentStatus.CAPTURED);
	private static final Set<StripePaymentIntentStatus> ALIVE = EnumSet.of(StripePaymentIntentStatus.AUTHORIZED,
			StripePaymentIntentStatus.CAPTURED, StripePaymentIntentStatus.PROCESSING);
	private static final Set<StripePaymentIntentStatus> DEAD = EnumSet.of(StripePaymentIntentStatus.FAILED,
			StripePaymentIntentStatus.CANCELED);
	private static final Set<StripePaymentIntentState> FUNDS_RELEASED = Set.of(StripePaymentIntentState.CANCELED,
			StripePaymentIntentState.REFUNDED);

	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private StripePaymentIntentRecordService stripePaymentIntentRecordService;
	private StripeGateway stripeGateway;
	private StripePaymentIntentService stripePaymentIntentService;
	private StripeConfigurationService stripeConfigurationService;
	private StripeOrderAuthorizationService stripeOrderAuthorizationService;
	private StripeOrderPlacementService stripeOrderPlacementService;
	private StripeOrderCancellationService stripeOrderCancellationService;
	private ModelService modelService;
	private TimeService timeService;

	@Override
	public StripeWebhookOutcome handle(final String eventType, final PaymentIntent paymentIntent) throws RetryLaterException
	{
		final boolean confirmation = CONFIRMATION_EVENTS.contains(eventType);
		if (!confirmation && !FAILURE_EVENTS.contains(eventType))
		{
			return StripeWebhookOutcome.IGNORED;
		}
		final String paymentIntentId = paymentIntent.getId();
		final Optional<StripePaymentIntentRecordModel> record = StringUtils.isBlank(paymentIntentId) ? Optional.empty()
				: stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntentId);
		if (record.isEmpty())
		{
			LOG.info("Payment intent {} of event {} has no record: it is not a payment of this shop", paymentIntentId,
					eventType);
			return StripeWebhookOutcome.IGNORED;
		}
		return confirmation ? onConfirmation(record.get()) : onFailure(eventType, record.get());
	}

	private StripeWebhookOutcome onConfirmation(final StripePaymentIntentRecordModel record) throws RetryLaterException
	{
		final String paymentIntentId = record.getPaymentIntentId();
		final Optional<OrderModel> order = orderOf(record);
		if (order.isEmpty())
		{
			waitForCustomer(record);
			final PaymentIntent live = stripeGateway.retrievePaymentIntent(paymentIntentId);
			final StripePaymentIntentStatus status = statusOf(live);
			if (!ALIVE.contains(status))
			{
				LOG.info("Payment intent {} has no order and status {}: no order is placed", paymentIntentId, status);
				updateState(record, status);
				return StripeWebhookOutcome.PROCESSED;
			}
			return placeOrder(record, live);
		}

		final PaymentIntent live = stripeGateway.retrievePaymentIntent(paymentIntentId);
		final StripePaymentIntentStatus status = statusOf(live);
		if (PAID.contains(status) && OrderStatus.CANCELLED.equals(order.get().getStatus()))
		{
			LOG.warn("Payment intent {} is paid but its order {} is cancelled: releasing the funds", paymentIntentId,
					order.get().getCode());
			stripePaymentIntentService.releaseFunds(paymentIntentId, "order " + order.get().getCode() + " is cancelled");
			return StripeWebhookOutcome.PROCESSED;
		}
		if (PAID.contains(status) && !stripeOrderAuthorizationService.authorizePendingOrder(order.get(), live))
		{
			throw new StripeWebhookException("Authorization of order " + order.get().getCode() + " by payment intent "
					+ paymentIntentId + " was rejected", false);
		}
		updateState(record, status);
		LOG.info("Payment intent {} of order {} has status {}", paymentIntentId, order.get().getCode(), status);
		return StripeWebhookOutcome.PROCESSED;
	}

	private StripeWebhookOutcome onFailure(final String eventType, final StripePaymentIntentRecordModel record)
	{
		final String paymentIntentId = record.getPaymentIntentId();
		final StripePaymentIntentStatus status = statusOf(stripeGateway.retrievePaymentIntent(paymentIntentId));
		if (!DEAD.contains(status))
		{
			LOG.info("Event {} of payment intent {} is out of date: the status is now {}", eventType, paymentIntentId, status);
			return StripeWebhookOutcome.IGNORED;
		}
		updateState(record, status);
		final Optional<OrderModel> order = orderOf(record);
		if (order.isPresent() && !stripeOrderAuthorizationService.hasAcceptedAuthorization(order.get(), paymentIntentId))
		{
			LOG.info("Payment intent {} has status {}: cancelling pending order {}", paymentIntentId, status,
					order.get().getCode());
			requireCancellationService().cancelForFailedPayment(order.get());
		}
		return StripeWebhookOutcome.PROCESSED;
	}

	/**
	 * The order of the record or, when the record has no order yet, the live order of the PaymentIntent (which is then
	 * linked). The second look also finds an order that a failed attempt saved without linking it.
	 */
	private Optional<OrderModel> orderOf(final StripePaymentIntentRecordModel record)
	{
		if (record.getOrder() != null)
		{
			return Optional.of(record.getOrder());
		}
		final String paymentIntentId = record.getPaymentIntentId();
		final Optional<OrderModel> order = stripePaymentIntentRecordService.findLiveOrder(paymentIntentId);
		order.ifPresent(found -> {
			LOG.info("Linking order {} to the record of payment intent {}", found.getCode(), paymentIntentId);
			stripePaymentIntentRecordService.linkOrder(paymentIntentId, found);
		});
		return order;
	}

	/**
	 * Gives the customer the grace period to come back and place the order.
	 */
	private void waitForCustomer(final StripePaymentIntentRecordModel record) throws RetryLaterException
	{
		if (record.getCreationtime() == null)
		{
			return;
		}
		final Instant now = timeService.getCurrentTime().toInstant();
		final Instant deadline = record.getCreationtime().toInstant().plus(stripeConfigurationService.getWebhookGracePeriod());
		if (now.isBefore(deadline))
		{
			final Duration wait = Duration.between(now, deadline);
			LOG.info("Payment intent {} has no order yet: waiting {} s for the customer", record.getPaymentIntentId(),
					wait.toSeconds());
			final RetryLaterException retry = new RetryLaterException(
					"Grace period of payment intent " + record.getPaymentIntentId());
			retry.setMethod(RetryLaterException.Method.LINEAR);
			retry.setDelay(wait.toMillis());
			throw retry;
		}
	}

	private StripeWebhookOutcome placeOrder(final StripePaymentIntentRecordModel record, final PaymentIntent live)
	{
		final String paymentIntentId = record.getPaymentIntentId();
		final Optional<OrderModel> placed = requirePlacementService().placeOrderFor(live);
		if (placed.isPresent())
		{
			LOG.info("Order {} placed from the server for payment intent {}", placed.get().getCode(), paymentIntentId);
			return StripeWebhookOutcome.PROCESSED;
		}
		modelService.refresh(record);
		if (record.getOrder() != null || FUNDS_RELEASED.contains(record.getState()))
		{
			LOG.info("Payment intent {} needs no order from the server: record state {}", paymentIntentId, record.getState());
			return StripeWebhookOutcome.PROCESSED;
		}
		throw new StripeWebhookException("Order placement of payment intent " + paymentIntentId + " runs on another path",
				true);
	}

	private void updateState(final StripePaymentIntentRecordModel record, final StripePaymentIntentStatus status)
	{
		final StripePaymentIntentState state = stateOf(status);
		if (state == null || state.equals(record.getState()) || StripePaymentIntentState.REFUNDED.equals(record.getState()))
		{
			return;
		}
		record.setState(state);
		modelService.save(record);
	}

	private static StripePaymentIntentState stateOf(final StripePaymentIntentStatus status)
	{
		switch (status)
		{
			case AUTHORIZED:
				return StripePaymentIntentState.AUTHORIZED;
			case CAPTURED:
				return StripePaymentIntentState.CAPTURED;
			case PROCESSING:
				return StripePaymentIntentState.PROCESSING;
			case FAILED:
				return StripePaymentIntentState.FAILED;
			case CANCELED:
				return StripePaymentIntentState.CANCELED;
			default:
				return null;
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
			throw new StripeWebhookException(e.getMessage(), false, e);
		}
	}

	private StripeOrderPlacementService requirePlacementService()
	{
		if (stripeOrderPlacementService == null)
		{
			throw new IllegalStateException("No StripeOrderPlacementService is configured");
		}
		return stripeOrderPlacementService;
	}

	private StripeOrderCancellationService requireCancellationService()
	{
		if (stripeOrderCancellationService == null)
		{
			throw new IllegalStateException("No StripeOrderCancellationService is configured");
		}
		return stripeOrderCancellationService;
	}

	public void setStripePaymentIntentRecordDao(final StripePaymentIntentRecordDao stripePaymentIntentRecordDao)
	{
		this.stripePaymentIntentRecordDao = stripePaymentIntentRecordDao;
	}

	public void setStripePaymentIntentRecordService(final StripePaymentIntentRecordService stripePaymentIntentRecordService)
	{
		this.stripePaymentIntentRecordService = stripePaymentIntentRecordService;
	}

	public void setStripeGateway(final StripeGateway stripeGateway)
	{
		this.stripeGateway = stripeGateway;
	}

	public void setStripePaymentIntentService(final StripePaymentIntentService stripePaymentIntentService)
	{
		this.stripePaymentIntentService = stripePaymentIntentService;
	}

	public void setStripeConfigurationService(final StripeConfigurationService stripeConfigurationService)
	{
		this.stripeConfigurationService = stripeConfigurationService;
	}

	public void setStripeOrderAuthorizationService(final StripeOrderAuthorizationService stripeOrderAuthorizationService)
	{
		this.stripeOrderAuthorizationService = stripeOrderAuthorizationService;
	}

	public void setStripeOrderPlacementService(final StripeOrderPlacementService stripeOrderPlacementService)
	{
		this.stripeOrderPlacementService = stripeOrderPlacementService;
	}

	public void setStripeOrderCancellationService(final StripeOrderCancellationService stripeOrderCancellationService)
	{
		this.stripeOrderCancellationService = stripeOrderCancellationService;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public void setTimeService(final TimeService timeService)
	{
		this.timeService = timeService;
	}
}
