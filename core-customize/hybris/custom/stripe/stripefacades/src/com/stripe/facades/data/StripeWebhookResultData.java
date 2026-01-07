package com.stripe.facades.data;

public class StripeWebhookResultData
{
    private String eventId;
    private boolean processed;
    private String message;

    public String getEventId()
    {
        return eventId;
    }

    public void setEventId(final String eventId)
    {
        this.eventId = eventId;
    }

    public boolean isProcessed()
    {
        return processed;
    }

    public void setProcessed(final boolean processed)
    {
        this.processed = processed;
    }

    public String getMessage()
    {
        return message;
    }

    public void setMessage(final String message)
    {
        this.message = message;
    }
}
