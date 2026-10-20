package com.stripe.core.order;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.payment.StripePaymentInfos;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.ordercancel.impl.DefaultOrderCancelPaymentServiceStrategy;
import de.hybris.platform.ordercancel.model.OrderCancelRecordEntryModel;
import de.hybris.platform.payment.PaymentService;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adjusts the Stripe payment of an order after a cancel. SAP first recalculates the order totals. Then, for a Stripe
 * order whose transaction has the ACCEPTED authorization of the PaymentIntent of the order, the live PaymentIntent
 * decides (in automatic capture mode Stripe captures the money before SAP has a CAPTURE entry):
 * <ul>
 * <li>captured: when the net captured amount (amount received minus the amount refunded on the latest charge) is above
 * the charge amount of the order, the difference is refunded (follow-on refund);</li>
 * <li>authorized and the order total is 0: the authorization is voided;</li>
 * <li>authorized and the order total is above 0: nothing; the capture of the order process charges the current total;</li>
 * <li>processing: nothing; the await and webhook paths handle the PaymentIntent when it is final;</li>
 * <li>any other status: nothing; there is no money to release.</li>
 * </ul>
 * Calling it again for the same cancel does not refund twice, because the net captured amount already subtracts the
 * earlier refunds, and a canceled PaymentIntent is not voided again. Other orders get only the SAP behaviour.
 */
public class StripeOrderCancelPaymentServiceStrategy extends DefaultOrderCancelPaymentServiceStrategy
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeOrderCancelPaymentServiceStrategy.class);

	private PaymentService paymentService;
	private StripeGateway stripeGateway;
	private StripeAmountConverter stripeAmountConverter;

	@Override
	public void recalculateOrderAndModifyPayments(final OrderModel order)
	{
		super.recalculateOrderAndModifyPayments(order);
		final String paymentIntentId = StripePaymentInfos.paymentIntentId(order);
		if (paymentIntentId != null)
		{
			adjustPayment(order, paymentIntentId);
		}
	}

	/**
	 * SAP uses this form for orders with a generic payment info only. A Stripe order gets the same recalculation and
	 * payment adjustment as in {@link #recalculateOrderAndModifyPayments(OrderModel)}.
	 */
	@Override
	public void recalculateOrderAndModifyPayments(final OrderModel order, final OrderCancelRecordEntryModel cancelEntry)
	{
		if (StripePaymentInfos.paymentIntentId(order) == null)
		{
			super.recalculateOrderAndModifyPayments(order, cancelEntry);
			return;
		}
		recalculateOrderAndModifyPayments(order);
	}

	private void adjustPayment(final OrderModel order, final String paymentIntentId)
	{
		PaymentTransactionModel transaction = null;
		PaymentTransactionEntryModel authorization = null;
		for (final PaymentTransactionModel candidate : transactionsOf(order))
		{
			final Optional<PaymentTransactionEntryModel> accepted = acceptedAuthorization(candidate, paymentIntentId);
			if (accepted.isPresent())
			{
				transaction = candidate;
				authorization = accepted.get();
				break;
			}
		}
		if (authorization == null)
		{
			LOG.info("Order {} has no accepted authorization of payment intent {}: no Stripe payment to adjust",
					order.getCode(), paymentIntentId);
			return;
		}

		final PaymentIntent paymentIntent = stripeGateway.retrievePaymentIntent(paymentIntentId);
		final StripePaymentIntentStatus status = StripePaymentIntentStatus.of(paymentIntent.getStatus());
		final long orderAmount = stripeAmountConverter.chargeAmountMinor(order);
		switch (status)
		{
			case CAPTURED:
				refundExcess(order, transaction, paymentIntent, orderAmount);
				break;
			case AUTHORIZED:
				if (orderAmount == 0L)
				{
					voidAuthorization(order, authorization);
				}
				break;
			case PROCESSING:
				LOG.info("Payment intent {} of order {} is processing: the payment is adjusted when it is final",
						paymentIntentId, order.getCode());
				break;
			default:
				LOG.info("Payment intent {} of order {} has status {}: no Stripe payment to adjust", paymentIntentId,
						order.getCode(), status);
				break;
		}
	}

	private void refundExcess(final OrderModel order, final PaymentTransactionModel transaction,
			final PaymentIntent paymentIntent, final long orderAmount)
	{
		final long netCaptured = netCaptured(paymentIntent);
		if (netCaptured <= orderAmount)
		{
			return;
		}
		final String currencyIso = order.getCurrency().getIsocode();
		final BigDecimal difference = stripeAmountConverter.fromMinorUnits(netCaptured - orderAmount, currencyIso);
		final PaymentTransactionEntryModel refund = paymentService.refundFollowOn(transaction, difference);
		if (isAccepted(refund))
		{
			LOG.info("Refunded {} {} of order {} (transaction {}) after a cancel", difference, currencyIso,
					order.getCode(), transaction.getCode());
		}
		else
		{
			LOG.error("Refund of {} {} of order {} (transaction {}) after a cancel failed: {} {}", difference,
					currencyIso, order.getCode(), transaction.getCode(), statusOf(refund), detailsOf(refund));
		}
	}

	private void voidAuthorization(final OrderModel order, final PaymentTransactionEntryModel authorization)
	{
		final PaymentTransactionEntryModel cancel = paymentService.cancel(authorization);
		if (isAccepted(cancel))
		{
			LOG.info("Released the authorization of order {} after a full cancel", order.getCode());
		}
		else
		{
			LOG.error("Release of the authorization of order {} after a full cancel failed: {} {}", order.getCode(),
					statusOf(cancel), detailsOf(cancel));
		}
	}

	/**
	 * The amount received minus the amount refunded on the latest charge. Without the charge, the amount received.
	 */
	private static long netCaptured(final PaymentIntent paymentIntent)
	{
		final long received = paymentIntent.getAmountReceived() == null ? 0L : paymentIntent.getAmountReceived();
		final Charge charge = paymentIntent.getLatestChargeObject();
		final long refunded = charge == null || charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded();
		return received - refunded;
	}

	private static Collection<PaymentTransactionModel> transactionsOf(final OrderModel order)
	{
		return order.getPaymentTransactions() == null ? List.of() : order.getPaymentTransactions();
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
}
