package com.stripe.core.config;

import java.util.Arrays;
import java.util.List;

public class StripeConfigurationProperties
{
    private String secretKey;
    private String publishableKey;
    private String webhookSecret;
    private String captureMode;
    private String statementDescriptor;
    private String descriptorSuffix;
    private boolean paymentModeEnabled;
    private List<String> manualCaptureMethods = Arrays.asList("card", "paypal");

    public String getSecretKey()
    {
        return secretKey;
    }

    public void setSecretKey(final String secretKey)
    {
        this.secretKey = secretKey;
    }

    public String getPublishableKey()
    {
        return publishableKey;
    }

    public void setPublishableKey(final String publishableKey)
    {
        this.publishableKey = publishableKey;
    }

    public String getWebhookSecret()
    {
        return webhookSecret;
    }

    public void setWebhookSecret(final String webhookSecret)
    {
        this.webhookSecret = webhookSecret;
    }

    public String getCaptureMode()
    {
        return captureMode;
    }

    public void setCaptureMode(final String captureMode)
    {
        this.captureMode = captureMode;
    }

    public String getStatementDescriptor()
    {
        return statementDescriptor;
    }

    public void setStatementDescriptor(final String statementDescriptor)
    {
        this.statementDescriptor = statementDescriptor;
    }

    public String getDescriptorSuffix()
    {
        return descriptorSuffix;
    }

    public void setDescriptorSuffix(final String descriptorSuffix)
    {
        this.descriptorSuffix = descriptorSuffix;
    }

    public boolean isPaymentModeEnabled()
    {
        return paymentModeEnabled;
    }

    public void setPaymentModeEnabled(final boolean paymentModeEnabled)
    {
        this.paymentModeEnabled = paymentModeEnabled;
    }

    public List<String> getManualCaptureMethods()
    {
        return manualCaptureMethods;
    }

    public void setManualCaptureMethods(final List<String> manualCaptureMethods)
    {
        this.manualCaptureMethods = manualCaptureMethods;
    }
}
