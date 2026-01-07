package com.stripe.core.commands.impl;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.payment.commands.CaptureCommand;
import de.hybris.platform.payment.commands.request.CaptureRequest;
import de.hybris.platform.payment.commands.result.AbstractResult;
import de.hybris.platform.payment.commands.result.CaptureResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static de.hybris.platform.servicelayer.util.ServicesUtil.validateParameterNotNull;

/**
 * Stripe implementation of {@link CaptureCommand}.
 * Captures a previously authorized payment.
 */
public class StripeCaptureCommand extends AbstractStripeCommand implements CaptureCommand {

    private static final Logger LOG = LoggerFactory.getLogger(StripeCaptureCommand.class);

    @Override
    public CaptureResult perform(final CaptureRequest captureRequest) {
        validateParameterNotNull(captureRequest, "CaptureRequest cannot be null");
        validateParameterNotNull(captureRequest.getMerchantTransactionCode(), "MerchantTransactionCode cannot be null");
        validateParameterNotNull(captureRequest.getCurrency(), "Currency cannot be null");
        validateParameterNotNull(captureRequest.getTotalAmount(), "TotalAmount cannot be null");

        LOG.info("Processing Stripe capture request for transaction [{}] and PI [{}]", captureRequest.getMerchantTransactionCode(), captureRequest.getRequestId());

        final String paymentIntentId = captureRequest.getRequestId();

        if (paymentIntentId == null) {
            LOG.error("Could not find PaymentIntent ID for transaction: {}", captureRequest.getMerchantTransactionCode());
            return (CaptureResult) createErrorResult(TransactionStatusDetails.INVALID_REQUEST);
        }

        try {
            final PaymentIntent paymentIntent = stripePaymentIntegrationService.capturePayment(
                    paymentIntentId,
                    captureRequest.getTotalAmount()
            );

            return buildCaptureResult(captureRequest, paymentIntent);

        } catch (final StripeException e) {
            LOG.error("Stripe exception while capturing payment for transaction {}: {}",
                    captureRequest.getMerchantTransactionCode(), e.getMessage());
            return (CaptureResult) handleStripeException(e);
        }
    }

    /**
     * Builds a successful CaptureResult from the Stripe PaymentIntent response.
     *
     * @param request       the original capture request
     * @param paymentIntent the Stripe PaymentIntent response
     * @return the capture result
     */
    protected CaptureResult buildCaptureResult(final CaptureRequest request, final PaymentIntent paymentIntent) {
        final CaptureResult result = new CaptureResult();

        result.setCurrency(request.getCurrency());
        result.setTotalAmount(request.getTotalAmount());
        result.setRequestId(request.getRequestId());
        result.setRequestToken(paymentIntent.getId());
        result.setRequestTime(timeService.getCurrentTime());

        final TransactionStatus status = mapPaymentIntentStatus(paymentIntent.getStatus());
        result.setTransactionStatus(status);

        if (TransactionStatus.ACCEPTED.equals(status)) {
            result.setTransactionStatusDetails(TransactionStatusDetails.SUCCESFULL);
        } else {
            result.setTransactionStatusDetails(TransactionStatusDetails.UNKNOWN_CODE);
        }

        LOG.info("Capture result for PaymentIntent {}: status={}, statusDetails={}",
                paymentIntent.getId(), status, result.getTransactionStatusDetails());

        return result;
    }

    @Override
    protected AbstractResult createErrorResult(final TransactionStatusDetails statusDetails) {
        final CaptureResult result = new CaptureResult();
        result.setTransactionStatus(TransactionStatus.ERROR);
        result.setTransactionStatusDetails(statusDetails);
        result.setRequestTime(timeService.getCurrentTime());
        return result;
    }
}
