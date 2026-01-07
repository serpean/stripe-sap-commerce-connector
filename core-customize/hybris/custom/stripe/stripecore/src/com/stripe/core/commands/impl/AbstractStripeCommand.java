package com.stripe.core.commands.impl;

import com.stripe.core.payment.request.StripeRequestFactory;
import com.stripe.core.payment.services.StripePaymentIntegrationService;
import com.stripe.core.payment.services.StripePaymentTransactionService;
import com.stripe.exception.CardException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import de.hybris.platform.payment.commands.result.AbstractResult;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.dto.TransactionStatusDetails;
import de.hybris.platform.servicelayer.time.TimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * Abstract base class for Stripe payment commands.
 * Provides common functionality for error handling and status mapping.
 */
public abstract class AbstractStripeCommand {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractStripeCommand.class);

    protected StripeRequestFactory stripeRequestFactory;
    protected StripePaymentIntegrationService stripePaymentIntegrationService;
    protected StripePaymentTransactionService stripePaymentTransactionService;
    protected TimeService timeService;

    /**
     * Maps a Stripe PaymentIntent status to Hybris TransactionStatus.
     *
     * @param stripeStatus the Stripe status string
     * @return the corresponding TransactionStatus
     */
    protected TransactionStatus mapPaymentIntentStatus(final String stripeStatus) {
        if (stripeStatus == null) {
            return TransactionStatus.ERROR;
        }

        switch (stripeStatus.toLowerCase()) {
            case "succeeded":
            case "requires_capture":
            case "canceled":
                return TransactionStatus.ACCEPTED;
            case "processing":
            case "requires_action":
                return TransactionStatus.REVIEW;
            case "requires_payment_method":
                return TransactionStatus.REJECTED;
            default:
                LOG.warn("Unknown Stripe PaymentIntent status: {}", stripeStatus);
                return TransactionStatus.ERROR;
        }
    }

    /**
     * Maps a Stripe Refund status to Hybris TransactionStatus.
     *
     * @param stripeStatus the Stripe refund status string
     * @return the corresponding TransactionStatus
     */
    protected TransactionStatus mapRefundStatus(final String stripeStatus) {
        if (stripeStatus == null) {
            return TransactionStatus.ERROR;
        }

        switch (stripeStatus.toLowerCase()) {
            case "succeeded":
                return TransactionStatus.ACCEPTED;
            case "pending":
                return TransactionStatus.REVIEW;
            case "failed":
            case "canceled":
                return TransactionStatus.REJECTED;
            default:
                LOG.warn("Unknown Stripe Refund status: {}", stripeStatus);
                return TransactionStatus.ERROR;
        }
    }

    /**
     * Handles a Stripe exception and returns an appropriate error result.
     *
     * @param exception the Stripe exception
     * @return the error result with appropriate status details
     */
    protected AbstractResult handleStripeException(final StripeException exception) {
        LOG.error("Stripe API error: {} - {}", exception.getCode(), exception.getMessage());

        final TransactionStatusDetails statusDetails;

        if (exception instanceof CardException) {
            // Card errors (declined, expired, etc.)
            statusDetails = TransactionStatusDetails.BANK_DECLINE;
        } else if (exception instanceof InvalidRequestException) {
            // Invalid parameters
            statusDetails = TransactionStatusDetails.INVALID_REQUEST;
        } else if (exception.getStatusCode() != null && exception.getStatusCode() >= 500) {
            // Server errors - communication problem
            statusDetails = TransactionStatusDetails.COMMUNICATION_PROBLEM;
        } else {
            // Other errors
            statusDetails = TransactionStatusDetails.UNKNOWN_CODE;
        }

        return createErrorResult(statusDetails);
    }

    /**
     * Handles an InterruptedException during async operations.
     *
     * @return the error result
     */
    protected AbstractResult handleInterruptedException() {
        LOG.error("Operation was interrupted");
        Thread.currentThread().interrupt();
        return createErrorResult(TransactionStatusDetails.COMMUNICATION_PROBLEM);
    }

    /**
     * Converts an amount to Stripe's smallest currency unit.
     *
     * @param amount   the amount in major currency units
     * @param currency the currency model
     * @return the amount in smallest currency units
     */
    protected Long convertToStripeAmount(final BigDecimal amount,
                                         final de.hybris.platform.core.model.c2l.CurrencyModel currency) {
        if (amount == null || currency == null) {
            return null;
        }
        return stripeRequestFactory.convertToStripeAmount(amount, currency.getIsocode());
    }

    /**
     * Creates an error result with the given status details.
     * Subclasses must implement this to return the appropriate result type.
     *
     * @param statusDetails the transaction status details
     * @return the error result
     */
    protected abstract AbstractResult createErrorResult(TransactionStatusDetails statusDetails);

    // Setters for Spring injection

    public void setStripeRequestFactory(final StripeRequestFactory stripeRequestFactory) {
        this.stripeRequestFactory = stripeRequestFactory;
    }

    public void setStripePaymentIntegrationService(final StripePaymentIntegrationService stripePaymentIntegrationService) {
        this.stripePaymentIntegrationService = stripePaymentIntegrationService;
    }

    public void setStripePaymentTransactionService(final StripePaymentTransactionService stripePaymentTransactionService) {
        this.stripePaymentTransactionService = stripePaymentTransactionService;
    }

    public void setTimeService(final TimeService timeService) {
        this.timeService = timeService;
    }
}
