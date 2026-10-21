package com.stripe.core.config.impl;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.core.config.StripeCaptureMode;
import de.hybris.bootstrap.annotations.UnitTest;
import de.hybris.platform.basecommerce.model.site.BaseSiteModel;
import de.hybris.platform.servicelayer.config.ConfigurationService;
import de.hybris.platform.site.BaseSiteService;
import org.apache.commons.configuration.BaseConfiguration;
import org.apache.commons.configuration.Configuration;
import org.junit.Before;
import org.junit.Test;

import java.time.Duration;
import java.util.Set;

@UnitTest
public class DefaultStripeConfigurationServiceTest
{
	private Configuration configuration;
	private DefaultStripeConfigurationService service;

	@Before
	public void setUp()
	{
		configuration = new BaseConfiguration();
		final ConfigurationService configurationService = mock(ConfigurationService.class);
		when(configurationService.getConfiguration()).thenReturn(configuration);
		final BaseSiteModel site = mock(BaseSiteModel.class);
		when(site.getUid()).thenReturn("electronics-spa");
		final BaseSiteService baseSiteService = mock(BaseSiteService.class);
		when(baseSiteService.getCurrentBaseSite()).thenReturn(site);
		service = new DefaultStripeConfigurationService();
		service.setConfigurationService(configurationService);
		service.setBaseSiteService(baseSiteService);
	}

	@Test
	public void siteOverrideWins()
	{
		configuration.setProperty("stripe.capture.mode", "manual");
		configuration.setProperty("stripe.capture.mode.electronics-spa", "automatic");

		assertEquals(StripeCaptureMode.AUTOMATIC, service.getCaptureMode());
	}

	@Test
	public void fallsBackToGlobal()
	{
		configuration.setProperty("stripe.capture.mode", "automatic");
		configuration.setProperty("stripe.capture.mode.other-site", "manual");

		assertEquals(StripeCaptureMode.AUTOMATIC, service.getCaptureMode());
	}

	@Test
	public void defaults()
	{
		assertEquals(StripeCaptureMode.MANUAL, service.getCaptureMode());
		final Set<String> methods = service.getManualCaptureMethods();
		assertEquals(3, methods.size());
		assertEquals(Set.of("card", "paypal", "klarna"), methods);
		assertEquals(Duration.ofSeconds(300), service.getWebhookGracePeriod());
		assertEquals(Duration.ofSeconds(86400), service.getPendingPaymentTimeout());
		assertEquals(Duration.ofSeconds(300), service.getPendingPaymentRetryDelay());
	}

	@Test
	public void readsOnEveryCall()
	{
		configuration.setProperty("stripe.secret.key", "sk_test_one");
		assertEquals("sk_test_one", service.getSecretKey());

		configuration.setProperty("stripe.secret.key", "sk_test_two");
		assertEquals("sk_test_two", service.getSecretKey());
	}

	@Test
	public void siteOverrideAppliesToDurations()
	{
		configuration.setProperty("stripe.webhook.graceperiod.seconds", "300");
		configuration.setProperty("stripe.webhook.graceperiod.seconds.electronics-spa", "60");

		assertEquals(Duration.ofSeconds(60), service.getWebhookGracePeriod());
	}
}
