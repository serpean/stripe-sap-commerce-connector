package com.stripe.core.order.impl;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.order.StripePaymentAdjustmentService;
import com.stripe.core.payment.StripePaymentInfos;
import com.stripe.core.payment.StripePaymentIntentChecks;
import com.stripe.core.payment.StripePaymentIntentService;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.AbstractOrderEntryModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.PaymentService;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The live PaymentIntent decides the adjustment (in automatic capture mode Stripe captures the money before SAP has a
 * CAPTURE entry). The target amount is 0 for a fully cancelled order (status CANCELLED, or every entry with quantity
 * 0), because the SAP cancel keeps the delivery cost; otherwise it is the Stripe charge amount of the order.
 * <ul>
 * <li>captured: the net captured amount above the target is refunded (follow-on refund);</li>
 * <li>authorized and target 0: the authorization is voided;</li>
 * <li>authorized and target above 0: nothing; the capture of the order process charges the current total;</li>
 * <li>processing: nothing; the await and webhook paths handle the PaymentIntent when it is final;</li>
 * <li>any other status: nothing; there is no money to release.</li>
 * </ul>
 * An order without an accepted authorization and with a fully cancelled order and a pending payment record releases its
 * PaymentIntent ({@link #adjustToOrderTotal}).
 * A repeated call does not refund twice: the net captured amount already subtracts the earlier refunds, and a canceled
 * PaymentIntent is not voided again.
 */
public class DefaultStripePaymentAdjustmentService implements StripePaymentAdjustmentService
{
	public static final String RETRY_RUNNER_BEAN = "stripePaymentAdjustmentTaskRunner";
	static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(30);

	private static final Set<StripePaymentIntentState> PENDING = Set.of(StripePaymentIntentState.PROCESSING,
			StripePaymentIntentState.AWAITING_FUNDS);
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripePaymentAdjustmentService.class);

	private PaymentService paymentService;
	private StripeGateway stripeGateway;
	private StripeAmountConverter stripeAmountConverter;
	private StripePaymentIntentChecks stripePaymentIntentChecks;
	private ModelService modelService;
	private TaskService taskService;
	private TimeService timeService;
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private StripePaymentIntentService stripePaymentIntentService;

	@Override
	public Optional<PaymentIntent> livePaymentIntent(final OrderModel order)
	{
		final String paymentIntentId = StripePaymentInfos.paymentIntentId(order);
		if (paymentIntentId == null || authorizationOf(order, paymentIntentId).isEmpty())
		{
			return Optional.empty();
		}
		return Optional.of(stripeGateway.retrievePaymentIntent(paymentIntentId));
	}

	@Override
	public boolean adjustToOrderTotal(final OrderModel order)
	{
		final Optional<PaymentIntent> paymentIntent = livePaymentIntent(order);
		if (paymentIntent.isEmpty())
		{
			LOG.info("Order {} has no accepted Stripe authorization: no Stripe payment to adjust", order.getCode());
			return releasePendingPayment(order);
		}
		return adjust(order, paymentIntent.get());
	}

	/**
	 * A fully cancelled order whose payment is still pending (record PROCESSING or AWAITING_FUNDS, no accepted
	 * authorization) leaves its PaymentIntent open at Stripe, and the customer could still pay it. The funds are
	 * released: Stripe cancels the PaymentIntent, and money already received goes back (see the cash balance check). A
	 * PaymentIntent in Stripe status processing (SEPA debit) cannot be cancelled: the webhook refunds it when it
	 * succeeds.
	 */
	private boolean releasePendingPayment(final OrderModel order)
	{
		final String paymentIntentId = StripePaymentInfos.paymentIntentId(order);
		if (paymentIntentId == null || !isFullyCancelled(order))
		{
			return true;
		}
		final boolean pending = stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntentId)
				.map(record -> PENDING.contains(record.getState())).orElse(false);
		if (!pending)
		{
			return true;
		}
		if ("processing".equals(stripeGateway.retrievePaymentIntent(paymentIntentId).getStatus()))
		{
			LOG.info("Payment intent {} of cancelled order {} is processing and cannot be cancelled: the webhook refunds it "
					+ "when it succeeds", paymentIntentId, order.getCode());
			return true;
		}
		stripePaymentIntentService.releaseFunds(paymentIntentId, "order " + order.getCode() + " is cancelled");
		return true;
	}

	@Override
	public boolean adjust(final OrderModel order, final PaymentIntent paymentIntent)
	{
		final String paymentIntentId = paymentIntent.getId();
		final Optional<AuthorizedTransaction> authorized = authorizationOf(order, paymentIntentId);
		if (authorized.isEmpty())
		{
			LOG.info("Order {} has no accepted authorization of payment intent {}: no Stripe payment to adjust",
					order.getCode(), paymentIntentId);
			return true;
		}
		final StripePaymentIntentStatus status = StripePaymentIntentStatus.of(paymentIntent);
		final long target = targetAmount(order);
		switch (status)
		{
			case CAPTURED:
				return refundExcess(order, authorized.get().transaction(), paymentIntent, target);
			case AUTHORIZED:
				return target != 0L || voidAuthorization(order, authorized.get().authorization());
			case PROCESSING:
			case AWAITING_FUNDS:
				LOG.info("Payment intent {} of order {} is pending ({}): the payment is adjusted when it is final",
						paymentIntentId, order.getCode(), status);
				return true;
			default:
				LOG.info("Payment intent {} of order {} has status {}: no Stripe payment to adjust", paymentIntentId,
						order.getCode(), status);
				return true;
		}
	}

	@Override
	public void scheduleRetry(final OrderModel order)
	{
		final TaskModel task = modelService.create(TaskModel.class);
		task.setRunnerBean(RETRY_RUNNER_BEAN);
		task.setContextItem(order);
		task.setExecutionDate(Date.from(timeService.getCurrentTime().toInstant().plus(FIRST_RETRY_DELAY)));
		taskService.scheduleTask(task);
		LOG.warn("Stripe payment of order {} is not adjusted yet: retry in {} s", order.getCode(),
				FIRST_RETRY_DELAY.toSeconds());
	}

	private long targetAmount(final OrderModel order)
	{
		return isFullyCancelled(order) ? 0L : stripeAmountConverter.chargeAmountMinor(order);
	}

	private static boolean isFullyCancelled(final OrderModel order)
	{
		if (OrderStatus.CANCELLED.equals(order.getStatus()))
		{
			return true;
		}
		final List<AbstractOrderEntryModel> entries = order.getEntries();
		return entries != null && !entries.isEmpty() && entries.stream()
				.allMatch(entry -> entry.getQuantity() == null || entry.getQuantity() == 0L);
	}

	private boolean refundExcess(final OrderModel order, final PaymentTransactionModel transaction,
			final PaymentIntent paymentIntent, final long target)
	{
		final long netCaptured = stripePaymentIntentChecks.netCaptured(paymentIntent);
		if (netCaptured <= target)
		{
			return true;
		}
		final String currencyIso = order.getCurrency().getIsocode();
		final BigDecimal difference = stripeAmountConverter.fromMinorUnits(netCaptured - target, currencyIso);
		final PaymentTransactionEntryModel refund = paymentService.refundFollowOn(transaction, difference);
		if (isAccepted(refund))
		{
			LOG.info("Refunded {} {} of order {} (transaction {}) after a cancel", difference, currencyIso,
					order.getCode(), transaction.getCode());
			return true;
		}
		LOG.warn("Refund of {} {} of order {} (transaction {}) after a cancel failed: {} {}", difference, currencyIso,
				order.getCode(), transaction.getCode(), statusOf(refund), detailsOf(refund));
		return false;
	}

	private boolean voidAuthorization(final OrderModel order, final PaymentTransactionEntryModel authorization)
	{
		final PaymentTransactionEntryModel cancel = paymentService.cancel(authorization);
		if (isAccepted(cancel))
		{
			LOG.info("Released the authorization of order {} after a full cancel", order.getCode());
			return true;
		}
		LOG.warn("Release of the authorization of order {} after a full cancel failed: {} {}", order.getCode(),
				statusOf(cancel), detailsOf(cancel));
		return false;
	}

	private static Optional<AuthorizedTransaction> authorizationOf(final OrderModel order, final String paymentIntentId)
	{
		final Collection<PaymentTransactionModel> transactions = order.getPaymentTransactions();
		if (transactions == null)
		{
			return Optional.empty();
		}
		for (final PaymentTransactionModel transaction : transactions)
		{
			final Collection<PaymentTransactionEntryModel> entries = transaction.getEntries();
			final Optional<PaymentTransactionEntryModel> accepted = entries == null ? Optional.empty() : entries.stream()
					.filter(Objects::nonNull)
					.filter(entry -> PaymentTransactionType.AUTHORIZATION.equals(entry.getType())
							&& TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus())
							&& paymentIntentId.equals(entry.getRequestId()))
					.findFirst();
			if (accepted.isPresent())
			{
				return Optional.of(new AuthorizedTransaction(transaction, accepted.get()));
			}
		}
		return Optional.empty();
	}

	private static boolean isAccepted(final PaymentTransactionEntryModel entry)
	{
		return entry != null && TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus());
	}

	private static String statusOf(final PaymentTransactionEntryModel entry)
	{
		return entry == null ? null : entry.getTransactionStatus();
	}

	private static String detailsOf(final PaymentTransactionEntryModel entry)
	{
		return entry == null ? null : entry.getTransactionStatusDetails();
	}

	private record AuthorizedTransaction(PaymentTransactionModel transaction, PaymentTransactionEntryModel authorization)
	{
	}

	public void setPaymentService(final PaymentService paymentService)
	{
		this.paymentService = paymentService;
	}

	public void setStripeGateway(final StripeGateway stripeGateway)
	{
		this.stripeGateway = stripeGateway;
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}

	public void setStripePaymentIntentChecks(final StripePaymentIntentChecks stripePaymentIntentChecks)
	{
		this.stripePaymentIntentChecks = stripePaymentIntentChecks;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public void setTaskService(final TaskService taskService)
	{
		this.taskService = taskService;
	}

	public void setTimeService(final TimeService timeService)
	{
		this.timeService = timeService;
	}

	public void setStripePaymentIntentRecordDao(final StripePaymentIntentRecordDao stripePaymentIntentRecordDao)
	{
		this.stripePaymentIntentRecordDao = stripePaymentIntentRecordDao;
	}

	public void setStripePaymentIntentService(final StripePaymentIntentService stripePaymentIntentService)
	{
		this.stripePaymentIntentService = stripePaymentIntentService;
	}
}
