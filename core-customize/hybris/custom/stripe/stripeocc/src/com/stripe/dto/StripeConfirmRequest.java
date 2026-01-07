package com.stripe.dto;

public class StripeConfirmRequest
{
    private String intentId;
    private String paymentMethodId;
    private boolean savePaymentMethod;

    public String getIntentId()
    {
        return intentId;
    }

    public void setIntentId(final String intentId)
    {
        this.intentId = intentId;
    }

    public String getPaymentMethodId()
    {
        return paymentMethodId;
    }

    public void setPaymentMethodId(final String paymentMethodId)
    {
        this.paymentMethodId = paymentMethodId;
    }

    public boolean isSavePaymentMethod()
    {
        return savePaymentMethod;
    }

    public void setSavePaymentMethod(final boolean savePaymentMethod)
    {
        this.savePaymentMethod = savePaymentMethod;
    }
}
