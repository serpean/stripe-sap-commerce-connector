package com.stripe.core.stripe;

public class StripeWebhookProcessResult {
    private final boolean processed;
    private final String message;

    public StripeWebhookProcessResult(final boolean processed, final String message) {
        this.processed = processed;
        this.message = message;
    }

    public boolean isProcessed() {
        return processed;
    }

    public String getMessage() {
        return message;
    }

    public static StripeWebhookProcessResult ack(final String message) {
        return new StripeWebhookProcessResult(true, message);
    }

    public static StripeWebhookProcessResult nack(final String message) {
        return new StripeWebhookProcessResult(false, message);
    }
}
