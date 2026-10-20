package com.stripe.core.commands.impl;

import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import de.hybris.platform.payment.commands.VoidCommand;
import de.hybris.platform.payment.commands.request.VoidRequest;
import de.hybris.platform.payment.commands.result.VoidResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Releases the money of a PaymentIntent. An authorized PaymentIntent is canceled. A captured PaymentIntent gets a full
 * refund of the amount received, so a void never leaves money charged without an order.
 */
public class StripeVoidCommand extends AbstractStripeCommand implements VoidCommand
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeVoidCommand.class);
	private static final Set<String> FAILED_REFUND_STATUSES = Set.of("failed", "canceled");

	@Override
	public VoidResult perform(final VoidRequest request)
	{
		final String paymentIntentId = requirePaymentIntentId(request.getRequestId());
		final String key = idempotencyKey("void-", request);
		final VoidResult result = new VoidResult();
		result.setRequestId(paymentIntentId);
		result.setRequestToken(paymentIntentId);
		result.setCurrency(request.getCurrency());
		result.setAmount(request.getTotalAmount());
		result.setRequestTime(getTimeService().getCurrentTime());

		try
		{
			final PaymentIntent paymentIntent = getStripeGateway().retrievePaymentIntent(paymentIntentId);
			if (hasStatus(paymentIntent, StripePaymentIntentStatus.CANCELED))
			{
				return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
			}
			if (hasStatus(paymentIntent, StripePaymentIntentStatus.AUTHORIZED))
			{
				final PaymentIntent canceled = getStripeGateway().cancelPaymentIntent(paymentIntentId, key);
				return hasStatus(canceled, StripePaymentIntentStatus.CANCELED)
						? fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL)
						: fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
			}
			if (hasStatus(paymentIntent, StripePaymentIntentStatus.CAPTURED))
			{
				return refundAll(paymentIntent, key, result, request);
			}
			LOG.warn("PaymentIntent [{}] cannot be voided: status [{}]", paymentIntentId, paymentIntent.getStatus());
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}
		catch (final StripeGatewayException e)
		{
			return error(result, e, request);
		}
	}

	private VoidResult refundAll(final PaymentIntent paymentIntent, final String key, final VoidResult result,
			final VoidRequest request)
	{
		final Refund refund = getStripeGateway().createRefund(paymentIntent.getId(), paymentIntent.getAmountReceived(), key);
		LOG.info("Voided captured PaymentIntent [{}] with refund [{}], status [{}]", paymentIntent.getId(), refund.getId(),
				refund.getStatus());
		if (FAILED_REFUND_STATUSES.contains(refund.getStatus()))
		{
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}
		return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
	}
}
