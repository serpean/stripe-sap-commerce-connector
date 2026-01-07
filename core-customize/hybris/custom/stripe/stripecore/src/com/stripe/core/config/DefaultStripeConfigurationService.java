package com.stripe.core.config;

import de.hybris.platform.servicelayer.config.ConfigurationService;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class DefaultStripeConfigurationService
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeConfigurationService.class);
    private static final String SECRET_KEY_PROP = "stripe.secret.key";
    private static final String PUBLISHABLE_KEY_PROP = "stripe.publishable.key";
    private static final String WEBHOOK_SECRET_PROP = "stripe.webhook.secret";
    private static final String CAPTURE_MODE_PROP = "stripe.capture.mode";
    private static final String STATEMENT_DESCRIPTOR_PROP = "stripe.statement.descriptor";
    private static final String DESCRIPTOR_SUFFIX_PROP = "stripe.descriptor.suffix";
    private static final String PAYMENT_MODE_PROP = "payment.mode.STRIPE.enabled";
    private static final String MANUAL_CAPTURE_METHODS_PROP = "stripe.capture.manual.methods";

    private ConfigurationService configurationService;

    public StripeConfigurationProperties load()
    {
        final Configuration configuration = configurationService.getConfiguration();
        final StripeConfigurationProperties properties = new StripeConfigurationProperties();
        properties.setSecretKey(configuration.getString(SECRET_KEY_PROP, ""));
        properties.setPublishableKey(configuration.getString(PUBLISHABLE_KEY_PROP, ""));
        properties.setWebhookSecret(configuration.getString(WEBHOOK_SECRET_PROP, ""));
        properties.setCaptureMode(configuration.getString(CAPTURE_MODE_PROP, "manual"));
        properties.setStatementDescriptor(configuration.getString(STATEMENT_DESCRIPTOR_PROP, ""));
        properties.setDescriptorSuffix(configuration.getString(DESCRIPTOR_SUFFIX_PROP, ""));
        properties.setPaymentModeEnabled(configuration.getBoolean(PAYMENT_MODE_PROP, false));
        properties.setManualCaptureMethods(parseManualCaptureMethods(
                configuration.getString(MANUAL_CAPTURE_METHODS_PROP, "card,paypal")));
        LOG.info("Stripe configuration loaded. captureMode={}, paymentModeEnabled={}, manualCaptureMethods={}",
                properties.getCaptureMode(), properties.isPaymentModeEnabled(), properties.getManualCaptureMethods());
        return properties;
    }

    protected List<String> parseManualCaptureMethods(final String value)
    {
        if (StringUtils.isBlank(value))
        {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toList());
    }

    public void setConfigurationService(final ConfigurationService configurationService)
    {
        this.configurationService = configurationService;
    }
}
