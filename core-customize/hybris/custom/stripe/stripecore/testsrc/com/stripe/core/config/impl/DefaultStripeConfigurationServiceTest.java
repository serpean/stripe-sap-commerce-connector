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
		assertEquals(Set.of("card", "link", "paypal", "amazon_pay", "revolut_pay", "mobilepay", "satispay", "klarna", "sequra",
				"scalapay", "billie", "mondu"), methods);
		assertEquals(Duration.ofSeconds(300), service.getWebhookGracePeriod());
		assertEquals(Duration.ofSeconds(86400), service.getPendingPaymentTimeout());
		assertEquals(Duration.ofSeconds(300), service.getPendingPaymentRetryDelay());
	}

	@Test
	public void pendingTimeoutPerMethod()
	{
		configuration.setProperty("stripe.payment.pending.timeout.sepa_debit.seconds", "864000");

		assertEquals(Duration.ofSeconds(864000), service.getPendingPaymentTimeout("sepa_debit"));
	}

	@Test
	public void pendingTimeoutPerMethodFallsBackToGlobal()
	{
		assertEquals(Duration.ofSeconds(86400), service.getPendingPaymentTimeout("card"));
		assertEquals(Duration.ofSeconds(86400), service.getPendingPaymentTimeout(null));
	}

	@Test
	public void pendingTimeoutPerMethodSiteOverride()
	{
		configuration.setProperty("stripe.payment.pending.timeout.sepa_debit.seconds", "864000");
		configuration.setProperty("stripe.payment.pending.timeout.sepa_debit.seconds.electronics-spa", "60");

		assertEquals(Duration.ofSeconds(60), service.getPendingPaymentTimeout("sepa_debit"));
	}

	@Test
	public void defaultManualMethods()
	{
		assertEquals(Set.of("card", "link", "paypal", "amazon_pay", "revolut_pay", "mobilepay", "satispay", "klarna", "sequra",
				"scalapay", "billie", "mondu"), service.getManualCaptureMethods());
	}

	@Test
	public void customerAndCashBalanceDefaults()
	{
		assertEquals(true, service.isCustomerCreationEnabled());
		assertEquals(Duration.ofSeconds(3600), service.getCashBalanceRefundDelay());
	}

	@Test
	public void deferredMethodDefaults()
	{
		assertEquals(Duration.ofSeconds(1036800), service.getPendingPaymentTimeout("multibanco"));
		assertEquals(Duration.ofSeconds(1209600), service.getPendingPaymentTimeout("customer_balance"));
		assertEquals(Duration.ofSeconds(864000), service.getPendingPaymentTimeout("sepa_debit"));
		assertEquals(Duration.ofSeconds(86400), service.getPendingPaymentTimeout("card"));
	}

	@Test
	public void pendingRetryPerMethod()
	{
		assertEquals(Duration.ofSeconds(3600), service.getPendingPaymentRetryDelay("multibanco"));
		assertEquals(Duration.ofSeconds(3600), service.getPendingPaymentRetryDelay("customer_balance"));
		assertEquals(Duration.ofSeconds(300), service.getPendingPaymentRetryDelay("sepa_debit"));
		assertEquals(Duration.ofSeconds(300), service.getPendingPaymentRetryDelay(null));
		configuration.setProperty("stripe.payment.pending.retry.multibanco.seconds", "7200");
		configuration.setProperty("stripe.payment.pending.retry.multibanco.seconds.electronics-spa", "60");
		assertEquals(Duration.ofSeconds(60), service.getPendingPaymentRetryDelay("multibanco"));
	}

	@Test
	public void customerCreationCanBeDisabled()
	{
		configuration.setProperty("stripe.customer.create", "false");

		assertEquals(false, service.isCustomerCreationEnabled());
	}

	@Test
	public void customerCreationSiteOverride()
	{
		configuration.setProperty("stripe.customer.create", "true");
		configuration.setProperty("stripe.customer.create.electronics-spa", "false");

		assertEquals(false, service.isCustomerCreationEnabled());
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
