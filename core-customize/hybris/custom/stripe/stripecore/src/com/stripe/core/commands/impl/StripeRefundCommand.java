package com.stripe.core.commands.impl;

import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.payment.StripeTransactionLookup;
import com.stripe.model.Refund;
import de.hybris.platform.payment.commands.FollowOnRefundCommand;
import de.hybris.platform.payment.commands.request.FollowOnRefundRequest;
import de.hybris.platform.payment.commands.result.RefundResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Refunds an amount of a captured PaymentIntent. The requestId of the result is the refund id. A pending refund is
 * accepted; the webhook confirms it later. The idempotency key does not change when SAP retries the refund. Without
 * exactly one live payment transaction for the PaymentIntent, the command does not call Stripe.
 */
public class StripeRefundCommand extends AbstractStripeCommand implements FollowOnRefundCommand<FollowOnRefundRequest>
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeRefundCommand.class);

	private StripeTransactionLookup stripeTransactionLookup;

	@Override
	public RefundResult perform(final FollowOnRefundRequest request)
	{
		final String paymentIntentId = requirePaymentIntentId(request.getRequestId());
		final RefundResult result = new RefundResult();
		result.setRequestId(paymentIntentId);
		result.setRequestToken(paymentIntentId);
		result.setCurrency(request.getCurrency());
		result.setTotalAmount(request.getTotalAmount());
		result.setPaymentProvider(request.getPaymentProvider());
		result.setRequestTime(getTimeService().getCurrentTime());

		final Optional<PaymentTransactionModel> transaction = stripeTransactionLookup.findByPaymentIntentId(paymentIntentId);
		if (transaction.isEmpty())
		{
			LOG.error("No single live payment transaction for PaymentIntent [{}]; refund of transaction [{}] not sent",
					paymentIntentId, request.getMerchantTransactionCode());
			return fill(result, request, TransactionStatus.ERROR, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}

		final long amount = toMinorUnits(request.getTotalAmount(), request.getCurrency());
		final String key = "refund-" + paymentIntentId + "-" + stripeTransactionLookup.countSettledRefunds(transaction.get())
				+ "-" + amount;
		final Refund refund;
		try
		{
			refund = getStripeGateway().createRefund(paymentIntentId, amount, key);
		}
		catch (final StripeGatewayException e)
		{
			return error(result, e, request);
		}

		result.setRequestId(refund.getId());
		LOG.info("Refund [{}] of PaymentIntent [{}] has status [{}]", refund.getId(), paymentIntentId, refund.getStatus());
		return fillFromRefund(result, request, refund);
	}

	public void setStripeTransactionLookup(final StripeTransactionLookup stripeTransactionLookup)
	{
		this.stripeTransactionLookup = stripeTransactionLookup;
	}
}
