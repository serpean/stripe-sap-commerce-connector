package com.stripe.core.commands.impl;

import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import de.hybris.platform.payment.commands.FollowOnRefundCommand;
import de.hybris.platform.payment.commands.request.AbstractRequest;
import de.hybris.platform.payment.commands.request.FollowOnRefundRequest;
import de.hybris.platform.payment.commands.result.AbstractResult;
import de.hybris.platform.payment.commands.result.RefundResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static de.hybris.platform.servicelayer.util.ServicesUtil.validateParameterNotNull;

/**
 * Stripe implementation of {@link FollowOnRefundCommand}.
 * Refunds a previously captured payment.
 */
public class StripeRefundCommand extends AbstractStripeCommand implements FollowOnRefundCommand<FollowOnRefundRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(StripeRefundCommand.class);

    @Override
    public RefundResult perform(final FollowOnRefundRequest refundRequest) {
        validateParameterNotNull(refundRequest, "FollowOnRefundRequest cannot be null");
        validateParameterNotNull(refundRequest.getMerchantTransactionCode(), "MerchantTransactionCode cannot be null");
        validateParameterNotNull(refundRequest.getCurrency(), "Currency cannot be null");

        LOG.info("Processing refund request for transaction: {}, amount: {}",
                refundRequest.getMerchantTransactionCode(), refundRequest.getTotalAmount());

        final String paymentIntentId = stripePaymentTransactionService
                .getPaymentReferenceFromTransactionEntryCode(refundRequest.getMerchantTransactionCode());

        if (paymentIntentId == null) {
            LOG.error("Could not find PaymentIntent ID for transaction: {}", refundRequest.getMerchantTransactionCode());
            return (RefundResult) createErrorResult(TransactionStatusDetails.INVALID_REQUEST);
        }

        try {
            // If amount is null or zero, Stripe will refund the full amount
            final BigDecimal refundAmount = refundRequest.getTotalAmount();

            final Refund refund = stripePaymentIntegrationService.refundPayment(paymentIntentId, refundAmount);

            return buildRefundResult(refundRequest, refund);

        } catch (final StripeException e) {
            LOG.error("Stripe exception while refunding payment for transaction {}: {}",
                    refundRequest.getMerchantTransactionCode(), e.getMessage());
            return (RefundResult) handleStripeException(e);
        }
    }

    /**
     * Builds a successful RefundResult from the Stripe Refund response.
     *
     * @param request the original refund request
     * @param refund  the Stripe Refund response
     * @return the refund result
     */
    protected RefundResult buildRefundResult(final FollowOnRefundRequest request, final Refund refund) {
        final RefundResult result = new RefundResult();

        result.setCurrency(request.getCurrency());
        result.setTotalAmount(request.getTotalAmount());
        result.setRequestId(request.getRequestId());
        result.setRequestToken(refund.getId());
        result.setRequestTime(timeService.getCurrentTime());

        final TransactionStatus status = mapRefundStatus(refund.getStatus());
        result.setTransactionStatus(status);

        if (TransactionStatus.ACCEPTED.equals(status)) {
            result.setTransactionStatusDetails(TransactionStatusDetails.SUCCESFULL);
        } else if (TransactionStatus.REVIEW.equals(status)) {
            result.setTransactionStatusDetails(TransactionStatusDetails.REVIEW_NEEDED);
        } else {
            result.setTransactionStatusDetails(TransactionStatusDetails.UNKNOWN_CODE);
        }

        LOG.info("Refund result for Refund {}: status={}, statusDetails={}",
                refund.getId(), status, result.getTransactionStatusDetails());

        return result;
    }

    @Override
    protected AbstractResult createErrorResult(final TransactionStatusDetails statusDetails) {
        final RefundResult result = new RefundResult();
        result.setTransactionStatus(TransactionStatus.ERROR);
        result.setTransactionStatusDetails(statusDetails);
        result.setRequestTime(timeService.getCurrentTime());
        return result;
    }
}
