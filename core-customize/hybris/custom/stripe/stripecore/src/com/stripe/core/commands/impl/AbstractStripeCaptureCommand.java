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
 * accepted without a call to Stripe.
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
			PaymentIntent paymentIntent = getStripeGateway().retrievePaymentIntent(paymentIntentId);
			if (hasStatus(paymentIntent, StripePaymentIntentStatus.AUTHORIZED))
			{
				paymentIntent = getStripeGateway().capturePaymentIntent(paymentIntentId,
						toMinorUnits(request.getTotalAmount(), request.getCurrency()), key);
			}
			if (hasStatus(paymentIntent, StripePaymentIntentStatus.CAPTURED))
			{
				return fill(result, request, TransactionStatus.ACCEPTED, TransactionStatusDetails.SUCCESFULL);
			}
			LOG.warn("PaymentIntent [{}] cannot be captured: status [{}]", paymentIntentId, paymentIntent.getStatus());
			return fill(result, request, TransactionStatus.REJECTED, TransactionStatusDetails.GENERAL_SYSTEM_ERROR);
		}
		catch (final StripeGatewayException e)
		{
			return error(result, e, request);
		}
	}
}
