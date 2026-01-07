package com.stripe.dto;

public class StripeWebhookResponseWsDTO
{
    private String processedEventId;
    private String status;
    private String message;

    public String getProcessedEventId()
    {
        return processedEventId;
    }

    public void setProcessedEventId(final String processedEventId)
    {
        this.processedEventId = processedEventId;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(final String status)
    {
        this.status = status;
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
