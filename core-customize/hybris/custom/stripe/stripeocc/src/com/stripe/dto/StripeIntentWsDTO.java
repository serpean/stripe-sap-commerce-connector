package com.stripe.dto;

public class StripeIntentWsDTO
{
    private String intentId;
    private String clientSecret;
    private String status;

    public String getIntentId()
    {
        return intentId;
    }

    public void setIntentId(final String intentId)
    {
        this.intentId = intentId;
    }

    public String getClientSecret()
    {
        return clientSecret;
    }

    public void setClientSecret(final String clientSecret)
    {
        this.clientSecret = clientSecret;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(final String status)
    {
        this.status = status;
    }
}
