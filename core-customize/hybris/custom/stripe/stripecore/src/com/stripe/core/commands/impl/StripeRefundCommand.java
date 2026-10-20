package com.stripe.core.commands.impl;

import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.model.Refund;
import de.hybris.platform.payment.commands.FollowOnRefundCommand;
import de.hybris.platform.payment.commands.request.FollowOnRefundRequest;
import de.hybris.platform.payment.commands.result.RefundResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Refunds an amount of a captured PaymentIntent. The requestId of the result is the refund id. A pending refund is
 * accepted; the webhook confirms it later.
 */
public class StripeRefundCommand extends AbstractStripeCommand implements FollowOnRefundCommand<FollowOnRefundRequest>
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeRefundCommand.class);
	private static final Set<String> FAILED_STATUSES = Set.of("failed", "canceled");

	@Override
	public RefundResult perform(final FollowOnRefundRequest request)
	{
		final String paymentIntentId = requirePaymentIntentId(request.getRequestId());
		final String key = idempotencyKey("refund-", request);
		final RefundResult result = new RefundResult();
		result.setRequestToken(paymentIntentId);
		result.setCurrency(request.getCurrency());
		result.setTotalAmount(request.getTotalAmount());
		result.setPaymentProvider(request.getPaymentProvider());
		result.setRequestTime(getTimeService().getCurrentTime());

		final Refund refund;
		try
		{
			refund = getStripeGateway().createRefund(paymentIntentId,
					toMinorUnits(request.getTotalAmount(), request.getCurrency()), key);
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
}
