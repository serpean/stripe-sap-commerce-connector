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

	/**
	 * Reads {@code stripe.payment.pending.timeout.<type>.seconds}. Falls back to the global timeout when the type is
	 * {@code null} or has no property.
	 */
	Duration getPendingPaymentTimeout(String paymentMethodType);

	Duration getPendingPaymentRetryDelay();

	boolean isCustomerCreationEnabled();

	Duration getCashBalanceRefundDelay();

	String getStatementDescriptor();

	String getStatementDescriptorSuffix();
}
