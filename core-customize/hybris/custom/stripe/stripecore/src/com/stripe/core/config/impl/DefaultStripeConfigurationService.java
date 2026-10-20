package com.stripe.core.config.impl;

import com.stripe.core.config.StripeCaptureMode;
import com.stripe.core.config.StripeConfigurationService;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.site.BaseSiteService;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class DefaultStripeConfigurationService implements StripeConfigurationService
{
	private static final String SECRET_KEY = "stripe.secret.key";
	private static final String PUBLISHABLE_KEY = "stripe.publishable.key";
	private static final String WEBHOOK_SECRET = "stripe.webhook.secret";
	private static final String CAPTURE_MODE = "stripe.capture.mode";
	private static final String MANUAL_METHODS = "stripe.capture.manual.methods";
	private static final String GRACE_PERIOD = "stripe.webhook.graceperiod.seconds";
	private static final String PENDING_TIMEOUT = "stripe.payment.pending.timeout.seconds";
	private static final String PENDING_RETRY = "stripe.payment.pending.retry.seconds";
	private static final String STATEMENT_DESCRIPTOR = "stripe.statement.descriptor";
	private static final String DESCRIPTOR_SUFFIX = "stripe.descriptor.suffix";

	private static final String DEFAULT_MANUAL_METHODS = "card,paypal,klarna";
	private static final long DEFAULT_GRACE_PERIOD_SECONDS = 300L;
	private static final long DEFAULT_PENDING_TIMEOUT_SECONDS = 86400L;
	private static final long DEFAULT_PENDING_RETRY_SECONDS = 300L;

	private ConfigurationService configurationService;
	private BaseSiteService baseSiteService;

	@Override
	public String getSecretKey()
	{
		return readString(SECRET_KEY, "");
	}

	@Override
	public String getPublishableKey()
	{
		return readString(PUBLISHABLE_KEY, "");
	}

	@Override
	public String getWebhookSecret()
	{
		return readString(WEBHOOK_SECRET, "");
	}

	@Override
	public StripeCaptureMode getCaptureMode()
	{
		final String value = readString(CAPTURE_MODE, "manual");
		return "automatic".equalsIgnoreCase(value.trim()) ? StripeCaptureMode.AUTOMATIC : StripeCaptureMode.MANUAL;
	}

	@Override
	public Set<String> getManualCaptureMethods()
	{
		final String value = readString(MANUAL_METHODS, DEFAULT_MANUAL_METHODS);
		if (StringUtils.isBlank(value))
		{
			return Collections.emptySet();
		}
		return Arrays.stream(value.split(","))
				.map(String::trim)
				.filter(StringUtils::isNotEmpty)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	@Override
	public Duration getWebhookGracePeriod()
	{
		return readSeconds(GRACE_PERIOD, DEFAULT_GRACE_PERIOD_SECONDS);
	}

	@Override
	public Duration getPendingPaymentTimeout()
	{
		return readSeconds(PENDING_TIMEOUT, DEFAULT_PENDING_TIMEOUT_SECONDS);
	}

	@Override
	public Duration getPendingPaymentRetryDelay()
	{
		return readSeconds(PENDING_RETRY, DEFAULT_PENDING_RETRY_SECONDS);
	}

	@Override
	public String getStatementDescriptor()
	{
		return readString(STATEMENT_DESCRIPTOR, "");
	}

	@Override
	public String getStatementDescriptorSuffix()
	{
		return readString(DESCRIPTOR_SUFFIX, "");
	}

	protected String readString(final String key, final String defaultValue)
	{
		final Configuration configuration = configurationService.getConfiguration();
		final String siteUid = currentSiteUid();
		if (siteUid != null)
		{
			final String siteValue = configuration.getString(key + "." + siteUid, null);
			if (siteValue != null)
			{
				return siteValue;
			}
		}
		return configuration.getString(key, defaultValue);
	}

	protected Duration readSeconds(final String key, final long defaultSeconds)
	{
		final String value = readString(key, null);
		if (StringUtils.isBlank(value))
		{
			return Duration.ofSeconds(defaultSeconds);
		}
		try
		{
			return Duration.ofSeconds(Long.parseLong(value.trim()));
		}
		catch (final NumberFormatException e)
		{
			return Duration.ofSeconds(defaultSeconds);
		}
	}

	protected String currentSiteUid()
	{
		final BaseSiteModel site = baseSiteService.getCurrentBaseSite();
		return site == null ? null : site.getUid();
	}

	public void setConfigurationService(final ConfigurationService configurationService)
	{
		this.configurationService = configurationService;
	}

	public void setBaseSiteService(final BaseSiteService baseSiteService)
	{
		this.baseSiteService = baseSiteService;
	}
}
