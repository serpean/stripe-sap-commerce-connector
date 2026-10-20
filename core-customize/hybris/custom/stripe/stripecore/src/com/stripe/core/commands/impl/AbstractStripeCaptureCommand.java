package com.stripe.core.commands.impl;

import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.payment.commands.request.CaptureRequest;
import de.hybris.platform.payment.commands.result.CaptureResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures the amount of a capture request. A PaymentIntent that Stripe already captured (automatic capture) is
 * accepted without a call to Stripe, but only when Stripe received the requested amount.
 */
public abstract class AbstractStripeCaptureCommand extends AbstractStripeCommand
{
	private static final Logger LOG = LoggerFactory.getLogger(AbstractStripeCaptureCommand.class);

	protected CaptureResult capture(final CaptureRequest request)
	{
		final String paymentIntentId = requirePaymentIntentId(request.getRequestId());
		final String key = idempotencyKey("capture-", request);
		final CaptureResult result = new CaptureResult();
		result.setRequestId(paymentIntentId);
		result.setRequestToken(paymentIntentId);
		result.setCurrency(request.getCurrency());
		result.setTotalAmount(request.getTotalAmount());
		result.setRequestTime(getTimeService().getCurrentTime());

		try
		{
			final long amount = toMinorUnits(request.getTotalAmount(), request.getCurrency());
			final PaymentIntent paymentIntent = getStripeGateway().retrievePaymentIntent(paymentIntentId);
			if (hasStatus(paymentIntent, StripePaymentIntentStatus.CAPTURED))
			{
				return alreadyCaptured(paymentIntent, amount, result, request);
			}
			if (hasStatus(paymentIntent, StripePaymentIntentStatus.AUTHORIZED))
			{
				final PaymentIntent captured = getStripeGateway().capturePaymentIntent(paymentIntentId, amount, key);
				if (hasStatus(captured, StripePaymentIntentStatus.CAPTURED))
				{
					return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
				}
			}
			LOG.warn("PaymentIntent [{}] cannot be captured: status [{}]", paymentIntentId, paymentIntent.getStatus());
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}
		catch (final StripeGatewayException e)
		{
			return error(result, e, request);
		}
	}

	/**
	 * Stripe captured the PaymentIntent before (automatic capture). The capture is accepted only for the amount that
	 * Stripe received.
	 */
	private CaptureResult alreadyCaptured(final PaymentIntent paymentIntent, final long amount, final CaptureResult result,
			final CaptureRequest request)
	{
		if (Long.valueOf(amount).equals(paymentIntent.getAmountReceived()))
		{
			return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
		}
		LOG.warn("PaymentIntent [{}] received [{}], not the requested [{}] of transaction [{}]", paymentIntent.getId(),
				paymentIntent.getAmountReceived(), amount, request.getMerchantTransactionCode());
		return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.INVALID_REQUEST);
	}
}
