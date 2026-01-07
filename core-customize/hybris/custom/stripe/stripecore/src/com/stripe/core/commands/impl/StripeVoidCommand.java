package com.stripe.core.commands.impl;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.payment.commands.VoidCommand;
import de.hybris.platform.payment.commands.request.VoidRequest;
import de.hybris.platform.payment.commands.result.AbstractResult;
import de.hybris.platform.payment.commands.result.VoidResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static de.hybris.platform.servicelayer.util.ServicesUtil.validateParameterNotNull;

/**
 * Stripe implementation of {@link VoidCommand}.
 * Cancels/voids a previously authorized payment before capture.
 */
public class StripeVoidCommand extends AbstractStripeCommand implements VoidCommand {

    private static final Logger LOG = LoggerFactory.getLogger(StripeVoidCommand.class);

    @Override
    public VoidResult perform(final VoidRequest voidRequest) {
        validateParameterNotNull(voidRequest, "VoidRequest cannot be null");
        validateParameterNotNull(voidRequest.getMerchantTransactionCode(), "MerchantTransactionCode cannot be null");

        LOG.info("Processing void request for transaction: {}", voidRequest.getMerchantTransactionCode());

        final String paymentIntentId = stripePaymentTransactionService
                .getPaymentReferenceFromTransactionEntryCode(voidRequest.getMerchantTransactionCode());

        if (paymentIntentId == null) {
            LOG.error("Could not find PaymentIntent ID for transaction: {}", voidRequest.getMerchantTransactionCode());
            return (VoidResult) createErrorResult(TransactionStatusDetails.INVALID_REQUEST);
        }

        try {
            final PaymentIntent paymentIntent = stripePaymentIntegrationService.voidPayment(paymentIntentId);

            return buildVoidResult(voidRequest, paymentIntent);

        } catch (final StripeException e) {
            LOG.error("Stripe exception while voiding payment for transaction {}: {}",
                    voidRequest.getMerchantTransactionCode(), e.getMessage());
            return (VoidResult) handleStripeException(e);
        }
    }

    /**
     * Builds a successful VoidResult from the Stripe PaymentIntent response.
     *
     * @param request       the original void request
     * @param paymentIntent the Stripe PaymentIntent response
     * @return the void result
     */
    protected VoidResult buildVoidResult(final VoidRequest request, final PaymentIntent paymentIntent) {
        final VoidResult result = new VoidResult();

        result.setRequestId(request.getRequestId());
        result.setRequestToken(paymentIntent.getId());
        result.setRequestTime(timeService.getCurrentTime());

        // For void, "canceled" status means success
        final String status = paymentIntent.getStatus();
        if ("canceled".equalsIgnoreCase(status)) {
            result.setTransactionStatus(TransactionStatus.ACCEPTED);
            result.setTransactionStatusDetails(TransactionStatusDetails.SUCCESFULL);
        } else {
            result.setTransactionStatus(mapPaymentIntentStatus(status));
            result.setTransactionStatusDetails(TransactionStatusDetails.UNKNOWN_CODE);
        }

        LOG.info("Void result for PaymentIntent {}: status={}, statusDetails={}",
                paymentIntent.getId(), result.getTransactionStatus(), result.getTransactionStatusDetails());

        return result;
    }

    @Override
    protected AbstractResult createErrorResult(final TransactionStatusDetails statusDetails) {
        final VoidResult result = new VoidResult();
        result.setTransactionStatus(TransactionStatus.ERROR);
        result.setTransactionStatusDetails(statusDetails);
        result.setRequestTime(timeService.getCurrentTime());
        return result;
    }
}
