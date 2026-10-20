package com.stripe.core.commands.impl;

import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.payment.StripeTransactionLookup;
import com.stripe.model.Refund;
import de.hybris.platform.payment.commands.FollowOnRefundCommand;
import de.hybris.platform.payment.commands.request.FollowOnRefundRequest;
import de.hybris.platform.payment.commands.result.RefundResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Refunds an amount of a captured PaymentIntent. The requestId of the result is the refund id. A pending refund is
 * accepted; the webhook confirms it later. The idempotency key does not change when SAP retries the refund.
 */
public class StripeRefundCommand extends AbstractStripeCommand implements FollowOnRefundCommand<FollowOnRefundRequest>
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeRefundCommand.class);
	private static final Set<String> FAILED_STATUSES = Set.of("failed", "canceled");

	private StripeTransactionLookup stripeTransactionLookup;

	@Override
	public RefundResult perform(final FollowOnRefundRequest request)
	{
		final String paymentIntentId = requirePaymentIntentId(request.getRequestId());
		final RefundResult result = new RefundResult();
		result.setRequestToken(paymentIntentId);
		result.setCurrency(request.getCurrency());
		result.setTotalAmount(request.getTotalAmount());
		result.setPaymentProvider(request.getPaymentProvider());
		result.setRequestTime(getTimeService().getCurrentTime());

		final long amount = toMinorUnits(request.getTotalAmount(), request.getCurrency());
		final Refund refund;
		try
		{
			refund = getStripeGateway().createRefund(paymentIntentId, amount, refundKey(paymentIntentId, amount));
		}
		catch (final StripeGatewayException e)
		{
			result.setRequestId(paymentIntentId);
			return error(result, e, request);
		}

		result.setRequestId(refund.getId());
		LOG.info("Refund [{}] of PaymentIntent [{}] has status [{}]", refund.getId(), paymentIntentId, refund.getStatus());
		if (FAILED_STATUSES.contains(refund.getStatus()))
		{
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}
		return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
	}

	/**
	 * Same key for every retry of the same refund: the PaymentIntent, the number of refunds SAP already accepted and the
	 * amount. A new refund after an accepted one gets a new key.
	 */
	private String refundKey(final String paymentIntentId, final long amount)
	{
		final long acceptedRefunds = stripeTransactionLookup.findByPaymentIntentId(paymentIntentId)
				.map(PaymentTransactionModel::getEntries)
				.map(entries -> entries.stream().filter(StripeRefundCommand::isAcceptedRefund).count())
				.orElse(0L);
		return "refund-" + paymentIntentId + "-" + acceptedRefunds + "-" + amount;
	}

	private static boolean isAcceptedRefund(final PaymentTransactionEntryModel entry)
	{
		return PaymentTransactionType.REFUND_FOLLOW_ON.equals(entry.getType())
				&& TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus());
	}

	public void setStripeTransactionLookup(final StripeTransactionLookup stripeTransactionLookup)
	{
		this.stripeTransactionLookup = stripeTransactionLookup;
	}
}
