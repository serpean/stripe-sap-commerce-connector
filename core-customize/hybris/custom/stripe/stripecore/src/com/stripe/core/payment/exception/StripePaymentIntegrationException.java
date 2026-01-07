package com.stripe.core.payment.exception;

/**
 * Exception thrown when there is an error during Stripe payment integration.
 */
public class StripePaymentIntegrationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StripePaymentIntegrationException(final String message) {
        super(message);
    }

    public StripePaymentIntegrationException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
