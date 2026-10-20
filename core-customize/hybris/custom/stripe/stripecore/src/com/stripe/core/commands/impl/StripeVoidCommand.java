package com.stripe.core.commands.impl;

import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.payment.StripePaymentIntentChecks;
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

/**
 * Releases the money of a PaymentIntent. An authorized PaymentIntent is canceled. A captured PaymentIntent gets a refund
 * of the net captured amount (amount received minus earlier refunds), so a void never leaves money charged without an
 * order; when nothing is left, the void is accepted without a call to Stripe. The cancel key depends only on the
 * PaymentIntent; the refund key also holds the net amount, so a retry after a partial refund gets a new key.
 */
public class StripeVoidCommand extends AbstractStripeCommand implements VoidCommand
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeVoidCommand.class);

	private StripePaymentIntentChecks stripePaymentIntentChecks;

	@Override
	public VoidResult perform(final VoidRequest request)
	{
		final String paymentIntentId = requirePaymentIntentId(request.getRequestId());
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
				final PaymentIntent canceled = getStripeGateway().cancelPaymentIntent(paymentIntentId, "void-" + paymentIntentId);
				return hasStatus(canceled, StripePaymentIntentStatus.CANCELED)
						? fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL)
						: fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
			}
			if (hasStatus(paymentIntent, StripePaymentIntentStatus.CAPTURED))
			{
				return refundAll(paymentIntent, result, request);
			}
			LOG.warn("PaymentIntent [{}] cannot be voided: status [{}]", paymentIntentId, paymentIntent.getStatus());
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}
		catch (final StripeGatewayException e)
		{
			return error(result, e, request);
		}
	}

	public void setStripePaymentIntentChecks(final StripePaymentIntentChecks stripePaymentIntentChecks)
	{
		this.stripePaymentIntentChecks = stripePaymentIntentChecks;
	}

	private VoidResult refundAll(final PaymentIntent paymentIntent, final VoidResult result, final VoidRequest request)
	{
		final long netCaptured = stripePaymentIntentChecks.netCaptured(paymentIntent);
		if (netCaptured <= 0L)
		{
			LOG.info("Captured PaymentIntent [{}] is already fully refunded: nothing to void", paymentIntent.getId());
			return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
		}
		final Refund refund = getStripeGateway().createRefund(paymentIntent.getId(), netCaptured,
				"void-refund-" + paymentIntent.getId() + "-" + netCaptured);
		LOG.info("Voided captured PaymentIntent [{}] with refund [{}], status [{}]", paymentIntent.getId(), refund.getId(),
				refund.getStatus());
		return fillFromRefund(result, request, refund);
	}
}
