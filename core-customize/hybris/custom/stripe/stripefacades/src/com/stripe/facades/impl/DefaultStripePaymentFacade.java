package com.stripe.facades.impl;

import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.facades.StripePaymentFacade;
import com.stripe.facades.data.StripeWebhookResultData;
import com.stripe.core.stripe.StripeClient;
import com.stripe.core.stripe.StripeWebhookProcessResult;
import com.stripe.core.stripe.StripeWebhookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DefaultStripePaymentFacade implements StripePaymentFacade {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultStripePaymentFacade.class);

    private StripeClient stripeClient;
    private StripeWebhookService stripeWebhookService;

    @Override
    public StripeWebhookResultData processWebhook(final String payload, final String signature) throws StripeException {
        final Event event = stripeClient.parseWebhook(payload, signature);
        LOG.info("Processing Stripe webhook event {}", event.getId());
        final StripeWebhookProcessResult coreResult = stripeWebhookService != null
                ? stripeWebhookService.processEvent(event)
                : StripeWebhookProcessResult.ack("No webhook service configured");
        final StripeWebhookResultData resultData = new StripeWebhookResultData();
        resultData.setEventId(event.getId());
        resultData.setProcessed(coreResult.isProcessed());
        resultData.setMessage(coreResult.getMessage());
        return resultData;
    }

    public void setStripeClient(final StripeClient stripeClient) {
        this.stripeClient = stripeClient;
    }

    public void setStripeWebhookService(final StripeWebhookService stripeWebhookService) {
        this.stripeWebhookService = stripeWebhookService;
    }
}
