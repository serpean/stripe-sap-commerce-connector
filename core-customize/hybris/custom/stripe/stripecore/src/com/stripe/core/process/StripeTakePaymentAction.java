package com.stripe.core.process;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.payment.StripePaymentInfos;
import de.hybris.platform.core.enums.OrderStatus;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.orderprocessing.model.OrderProcessModel;
import de.hybris.platform.payment.PaymentService;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.processengine.action.AbstractSimpleDecisionAction;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the capture action of the order process ({@code takePaymentAction}). For a Stripe order, it captures the
 * current charge amount of the order on the transaction whose ACCEPTED authorization belongs to the PaymentIntent of the
 * order: a partial capture when the order total went down (partial cancel), a full capture otherwise. Other
 * transactions (for example, of a PaymentIntent released at checkout) are skipped. When the order total is above the
 * authorized amount, nothing is captured. The order gets PAYMENT_CAPTURED (OK) or PAYMENT_NOT_CAPTURED (NOK). The
 * wrapped action does not run for Stripe orders; other orders go to it.
 */
public class StripeTakePaymentAction extends AbstractSimpleDecisionAction<OrderProcessModel>
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeTakePaymentAction.class);

	private final AbstractSimpleDecisionAction<OrderProcessModel> delegate;
	private PaymentService paymentService;
	private StripeAmountConverter stripeAmountConverter;

	public StripeTakePaymentAction(final AbstractSimpleDecisionAction<OrderProcessModel> delegate)
	{
		this.delegate = delegate;
	}

	@Override
	public Transition executeAction(final OrderProcessModel process) throws Exception
	{
		final OrderModel order = process.getOrder();
		final String paymentIntentId = StripePaymentInfos.paymentIntentId(order);
		if (paymentIntentId == null)
		{
			return delegate.executeAction(process);
		}

		PaymentTransactionModel authorized = null;
		PaymentTransactionEntryModel authorization = null;
		for (final PaymentTransactionModel transaction : transactionsOf(order))
		{
			final Optional<PaymentTransactionEntryModel> accepted = acceptedAuthorization(transaction, paymentIntentId);
			if (accepted.isPresent() && authorization == null)
			{
				authorized = transaction;
				authorization = accepted.get();
			}
			else
			{
				LOG.warn("Transaction {} of order {} is not the authorization of payment intent {}: it is not captured",
						transaction.getCode(), order.getCode(), paymentIntentId);
			}
		}
		if (authorization == null)
		{
			LOG.error("Order {} has no accepted authorization of payment intent {}: nothing is captured", order.getCode(),
					paymentIntentId);
			return notCaptured(order);
		}
		return capture(order, authorized, authorization);
	}

	private Transition capture(final OrderModel order, final PaymentTransactionModel transaction,
			final PaymentTransactionEntryModel authorization)
	{
		final String currencyIso = order.getCurrency().getIsocode();
		final BigDecimal amount = stripeAmountConverter.chargeAmount(order);
		final long amountMinor = stripeAmountConverter.toMinorUnits(amount, currencyIso);
		final long authorizedMinor = stripeAmountConverter.toMinorUnits(authorization.getAmount(), currencyIso);
		if (amountMinor > authorizedMinor)
		{
			LOG.error("Order {} total {} is above the authorized amount {} of transaction {}: nothing is captured",
					order.getCode(), amount, authorization.getAmount(), transaction.getCode());
			return notCaptured(order);
		}

		final PaymentTransactionEntryModel entry = amountMinor < authorizedMinor
				? paymentService.partialCapture(transaction, amount)
				: paymentService.capture(transaction);
		if (TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus()))
		{
			LOG.info("Captured {} {} of order {} (transaction {})", amount, currencyIso, order.getCode(),
					transaction.getCode());
			setOrderStatus(order, OrderStatus.PAYMENT_CAPTURED);
			return Transition.OK;
		}
		LOG.error("Capture of order {} (transaction {}) failed: {} {}", order.getCode(), transaction.getCode(),
				entry.getTransactionStatus(), entry.getTransactionStatusDetails());
		return notCaptured(order);
	}

	private Transition notCaptured(final OrderModel order)
	{
		setOrderStatus(order, OrderStatus.PAYMENT_NOT_CAPTURED);
		return Transition.NOK;
	}

	private static List<PaymentTransactionModel> transactionsOf(final OrderModel order)
	{
		return order.getPaymentTransactions() == null ? List.of() : List.copyOf(order.getPaymentTransactions());
	}

	private static Optional<PaymentTransactionEntryModel> acceptedAuthorization(final PaymentTransactionModel transaction,
			final String paymentIntentId)
	{
		final Collection<PaymentTransactionEntryModel> entries = transaction.getEntries();
		return entries == null ? Optional.empty() : entries.stream()
				.filter(Objects::nonNull)
				.filter(entry -> PaymentTransactionType.AUTHORIZATION.equals(entry.getType())
						&& TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus())
						&& paymentIntentId.equals(entry.getRequestId()))
				.findFirst();
	}

	public void setPaymentService(final PaymentService paymentService)
	{
		this.paymentService = paymentService;
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}
}
