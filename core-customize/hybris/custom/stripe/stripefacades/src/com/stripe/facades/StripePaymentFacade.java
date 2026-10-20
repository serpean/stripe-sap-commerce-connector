package com.stripe.facades;

import com.stripe.exception.StripeException;
import com.stripe.facades.data.StripeWebhookResultData;

public interface StripePaymentFacade {
    StripeWebhookResultData processWebhook(String payload, String signature) throws StripeException;
}
