package com.stripe.exception;

import de.hybris.platform.webservicescommons.errors.exceptions.WebserviceException;

/**
 * Generic exception for Stripe payment errors in OCC API.
 */
public class StripeGenericWebserviceException extends WebserviceException {
    private static final long serialVersionUID = 1L;
    private static final String TYPE = "StripePaymentError";
    private static final String SUBJECT_TYPE = "stripe";

    public StripeGenericWebserviceException(final String message) {
        super(message);
    }

    public StripeGenericWebserviceException(final String message, final String reason) {
        super(message, reason);
    }

    public StripeGenericWebserviceException(final String message, final String reason, final Throwable cause) {
        super(message, reason, cause);
    }

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getSubjectType() {
        return SUBJECT_TYPE;
    }
}
