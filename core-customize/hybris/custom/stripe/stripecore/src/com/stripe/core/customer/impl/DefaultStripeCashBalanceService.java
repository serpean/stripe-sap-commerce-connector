package com.stripe.core.customer.impl;

import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.customer.StripeCashBalanceService;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import de.hybris.platform.servicelayer.time.TimeService;
import de.hybris.platform.task.TaskModel;
import de.hybris.platform.task.TaskService;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stripe decides if a bank transfer can still use the balance: the customer has an open bank transfer when one of the
 * open PaymentIntents of the customer (requires_action, requires_confirmation, processing) is a bank transfer awaiting funds
 * ({@link StripePaymentIntentStatus#AWAITING_FUNDS} with bank transfer instructions; a voucher payment cannot use the
 * balance). Local records are not used: they hold neither the payment method nor the Stripe customer. The Stripe
 * customer id is enough, so guests (one Stripe customer for each cart, not stored) work too.
 * <p>
 * The gateway reads the first 100 PaymentIntents of the customer (newest first), which is more than any customer has
 * open.
 */
public class DefaultStripeCashBalanceService implements StripeCashBalanceService
{
	public static final String REFUND_RUNNER_BEAN = "stripeCashBalanceRefundTaskRunner";
	public static final String CONTEXT_PREFIX = "cash-refund-check:";

	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeCashBalanceService.class);
	private static final String BANK_TRANSFER_INSTRUCTIONS = "display_bank_transfer_instructions";
	private static final String PENDING_TASKS = "SELECT {pk} FROM {Task} WHERE {runnerBean} = ?runner "
			+ "AND ({failed} IS NULL OR {failed} = ?notFailed) AND {executionDate} > ?now";

	private StripeGateway stripeGateway;
	private StripeConfigurationService stripeConfigurationService;
	private ModelService modelService;
	private TaskService taskService;
	private TimeService timeService;
	private FlexibleSearchService flexibleSearchService;
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;

	@Override
	public void scheduleRefundCheck(final String customerId, final String currency)
	{
		final String key = contextOf(customerId, currency);
		if (hasPendingCheck(key))
		{
			LOG.info("A cash balance check of Stripe customer {} ({}) is already pending", customerId, currency);
			return;
		}
		final TaskModel task = modelService.create(TaskModel.class);
		task.setRunnerBean(REFUND_RUNNER_BEAN);
		task.setContext(key);
		final long delay = stripeConfigurationService.getCashBalanceRefundDelay().toMillis();
		task.setExecutionDate(new Date(timeService.getCurrentTime().getTime() + delay));
		taskService.scheduleTask(task);
		LOG.info("Cash balance check of Stripe customer {} ({}) planned in {} s", customerId, currency, delay / 1000);
	}

	@Override
	public void refundUnappliedBalance(final String customerId, final String currency)
	{
		final long balance = stripeGateway.retrieveCashBalance(customerId, currency);
		if (balance <= 0L)
		{
			LOG.info("Stripe customer {} has no cash balance in {}: nothing to refund", customerId, currency);
			return;
		}
		if (hasOpenBankTransfer(customerId, currency))
		{
			LOG.info("Stripe customer {} has an open bank transfer: the cash balance of {} {} is kept; checked again later",
					customerId, balance, currency);
			scheduleRefundCheck(customerId, currency);
			return;
		}
		final String latestTransaction = stripeGateway.retrieveLatestCashBalanceTransactionId(customerId);
		final String key = refundKey(customerId, currency, latestTransaction, balance);
		stripeGateway.createCashBalanceRefund(customerId, balance, currency, key);
		LOG.info("Refunded the cash balance of Stripe customer {}: {} {} minor units", customerId, balance, currency);
	}

	static String refundKey(final String customerId, final String currency, final String latestTransactionId,
			final long balance)
	{
		return "cash-refund-" + customerId + "-" + currency.toLowerCase(Locale.ROOT) + "-" + latestTransactionId + "-"
				+ balance;
	}

	public static String contextOf(final String customerId, final String currency)
	{
		return CONTEXT_PREFIX + customerId + ":" + currency.toLowerCase(Locale.ROOT);
	}

	private boolean hasOpenBankTransfer(final String customerId, final String currency)
	{
		final List<PaymentIntent> open = stripeGateway.listOpenPaymentIntents(customerId);
		return open != null && open.stream().anyMatch(
				paymentIntent -> currency.equalsIgnoreCase(paymentIntent.getCurrency())
						&& isBankTransferAwaitingFunds(paymentIntent)
						&& stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntent.getId()).isPresent());
	}

	private static boolean isBankTransferAwaitingFunds(final PaymentIntent paymentIntent)
	{
		return StripePaymentIntentStatus.of(paymentIntent) == StripePaymentIntentStatus.AWAITING_FUNDS
				&& paymentIntent.getNextAction() != null
				&& BANK_TRANSFER_INSTRUCTIONS.equals(paymentIntent.getNextAction().getType());
	}

	/**
	 * A check is pending when it is planned for later. A task that is running now is due, so it does not count: the
	 * follow-up of a kept balance can be planned from inside the running check.
	 */
	private boolean hasPendingCheck(final String key)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(PENDING_TASKS);
		query.addQueryParameter("runner", REFUND_RUNNER_BEAN);
		query.addQueryParameter("notFailed", Boolean.FALSE);
		query.addQueryParameter("now", timeService.getCurrentTime());
		final List<TaskModel> tasks = flexibleSearchService.<TaskModel>search(query).getResult();
		return tasks.stream().anyMatch(task -> key.equals(task.getContext()));
	}

	public void setStripeGateway(final StripeGateway stripeGateway)
	{
		this.stripeGateway = stripeGateway;
	}

	public void setStripeConfigurationService(final StripeConfigurationService stripeConfigurationService)
	{
		this.stripeConfigurationService = stripeConfigurationService;
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

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}
}
