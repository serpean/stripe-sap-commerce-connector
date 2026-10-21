package com.stripe.core.commands.impl;

import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.payment.commands.request.CaptureRequest;
import de.hybris.platform.payment.commands.result.CaptureResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures the amount of a capture request. A PaymentIntent that Stripe already captured (automatic capture) is
 * accepted without a call to Stripe, but only when the amount Stripe holds (received minus refunded) is the requested
 * amount.
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
	 * Stripe captured the PaymentIntent before (automatic capture). The capture is accepted only for the net amount that
	 * Stripe holds: the amount received minus the amount refunded on the latest charge (for example, after a partial
	 * cancel). When the charge details are missing, the amount received is used.
	 */
	private CaptureResult alreadyCaptured(final PaymentIntent paymentIntent, final long amount, final CaptureResult result,
			final CaptureRequest request)
	{
		final long received = paymentIntent.getAmountReceived() == null ? 0L : paymentIntent.getAmountReceived();
		final Charge charge = paymentIntent.getLatestChargeObject();
		final long refunded = charge == null || charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded();
		final long net = received - refunded;
		if (net == amount)
		{
			return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
		}
		LOG.warn("PaymentIntent [{}] holds [{}] (received [{}], refunded [{}]), not the requested [{}] of transaction [{}]",
				paymentIntent.getId(), net, received, refunded, amount, request.getMerchantTransactionCode());
		return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.INVALID_REQUEST);
	}
}
