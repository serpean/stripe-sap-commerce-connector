package com.stripe.core.config;

import java.time.Duration;
import java.util.Set;

/**
 * Reads the Stripe settings. Each method reads the configuration on every call.
 * It looks for {@code stripe.<name>.<currentBaseSiteUid>} first, then for {@code stripe.<name>}.
 */
public interface StripeConfigurationService
{
	String getSecretKey();

	String getPublishableKey();

	String getWebhookSecret();

	StripeCaptureMode getCaptureMode();

	Set<String> getManualCaptureMethods();

	Duration getWebhookGracePeriod();

	Duration getPendingPaymentTimeout();

	Duration getPendingPaymentRetryDelay();

	String getStatementDescriptor();

	String getStatementDescriptorSuffix();
}
