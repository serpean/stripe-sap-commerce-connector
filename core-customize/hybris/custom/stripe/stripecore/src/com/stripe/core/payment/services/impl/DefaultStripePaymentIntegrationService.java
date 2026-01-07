package com.stripe.core.payment.services.impl;

import com.stripe.core.payment.exception.StripePaymentIntegrationException;
import com.stripe.core.payment.services.StripePaymentIntegrationService;
import com.stripe.core.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * Default implementation of {@link StripePaymentIntegrationService}.
 * Delegates to {@link StripeClient} for actual API calls.
 */
public class DefaultStripePaymentIntegrationService implements StripePaymentIntegrationService {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultStripePaymentIntegrationService.class);

    private StripeClient stripeClient;

    @Override
    public PaymentIntent capturePayment(final String paymentIntentId, final BigDecimal amount) throws StripeException {
        validatePaymentIntentId(paymentIntentId, "capture");

        LOG.info("Capturing payment for PaymentIntent: {} with amount: {}", paymentIntentId, amount);

        try {
            final PaymentIntent result = stripeClient.capture(paymentIntentId, amount);
            LOG.info("Successfully captured payment for PaymentIntent: {}, status: {}",
                    paymentIntentId, result.getStatus());
            return result;
        } catch (final StripeException e) {
            LOG.error("Failed to capture payment for PaymentIntent: {}. Error: {}",
                    paymentIntentId, e.getMessage());
            throw e;
        }
    }

    @Override
    public PaymentIntent voidPayment(final String paymentIntentId) throws StripeException {
        validatePaymentIntentId(paymentIntentId, "void");

        LOG.info("Voiding/canceling PaymentIntent: {}", paymentIntentId);

        try {
            final PaymentIntent result = stripeClient.cancel(paymentIntentId);
            LOG.info("Successfully voided PaymentIntent: {}, status: {}",
                    paymentIntentId, result.getStatus());
            return result;
        } catch (final StripeException e) {
            LOG.error("Failed to void PaymentIntent: {}. Error: {}",
                    paymentIntentId, e.getMessage());
            throw e;
        }
    }

    @Override
    public Refund refundPayment(final String paymentIntentId, final BigDecimal amount) throws StripeException {
        validatePaymentIntentId(paymentIntentId, "refund");

        LOG.info("Refunding PaymentIntent: {} with amount: {}", paymentIntentId, amount);

        try {
            final Refund result = stripeClient.refund(paymentIntentId, amount);
            LOG.info("Successfully created refund for PaymentIntent: {}, refund ID: {}, status: {}",
                    paymentIntentId, result.getId(), result.getStatus());
            return result;
        } catch (final StripeException e) {
            LOG.error("Failed to refund PaymentIntent: {}. Error: {}",
                    paymentIntentId, e.getMessage());
            throw e;
        }
    }

    @Override
    public PaymentIntent getPaymentIntent(final String paymentIntentId) throws StripeException {
        validatePaymentIntentId(paymentIntentId, "retrieve");

        LOG.debug("Retrieving PaymentIntent: {}", paymentIntentId);

        try {
            // We use the capture method with null amount to just retrieve
            // Actually we need a retrieve method - let's add proper retrieval
            // For now, we can use the existing stripeClient methods
            // Note: StripeClient.capture retrieves then captures, so we need to be careful
            // Let's throw an exception for now until we add a proper retrieve method
            throw new StripePaymentIntegrationException(
                    "PaymentIntent retrieval not yet implemented. Use capture, void, or refund operations directly.");
        } catch (final StripePaymentIntegrationException e) {
            LOG.error("Failed to retrieve PaymentIntent: {}. Error: {}",
                    paymentIntentId, e.getMessage());
            throw e;
        }
    }

    protected void validatePaymentIntentId(final String paymentIntentId, final String operation) {
        if (StringUtils.isBlank(paymentIntentId)) {
            final String message = String.format("PaymentIntent ID is required for %s operation", operation);
            LOG.error(message);
            throw new StripePaymentIntegrationException(message);
        }

        if (!paymentIntentId.startsWith("pi_")) {
            LOG.warn("PaymentIntent ID {} does not start with 'pi_', this may not be a valid PaymentIntent ID",
                    paymentIntentId);
        }
    }

    // Setter for Spring injection

    public void setStripeClient(final StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }
}
