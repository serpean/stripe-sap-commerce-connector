package com.stripe.core.gateway.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.Stripe;
import com.stripe.core.config.StripeConfigurationService;
import com.stripe.core.gateway.StripeGatewayException;
import com.stripe.param.PaymentIntentCreateParams;
import de.hybris.bootstrap.annotations.UnitTest;
import org.junit.Before;
import org.junit.Test;

@UnitTest
public class DefaultStripeGatewayTest
{
	private StripeConfigurationService configuration;
	private DefaultStripeGateway gateway;

	@Before
	public void setUp()
	{
		configuration = mock(StripeConfigurationService.class);
		gateway = new DefaultStripeGateway();
		gateway.setStripeConfigurationService(configuration);
	}

	@Test
	public void apiVersionIsPinned()
	{
		assertEquals("2025-09-30.clover", DefaultStripeGateway.API_VERSION);
		assertEquals(Stripe.API_VERSION, DefaultStripeGateway.API_VERSION);
	}

	@Test
	public void invalidSignatureIsReported()
	{
		when(configuration.getWebhookSecret()).thenReturn("whsec_test");

		try
		{
			gateway.constructEvent("{}", "t=1,v1=bad");
			fail("A bad signature must throw");
		}
		catch (final StripeGatewayException e)
		{
			assertTrue(e.isSignatureInvalid());
			assertFalse(e.isRetryable());
		}
	}

	@Test
	public void missingSecretKeyFailsFast()
	{
		when(configuration.getSecretKey()).thenReturn("");
		final PaymentIntentCreateParams params = PaymentIntentCreateParams.builder().setAmount(1000L).setCurrency("eur").build();

		try
		{
			gateway.createPaymentIntent(params, "cart-1");
			fail("A missing key must throw");
		}
		catch (final StripeGatewayException e)
		{
			assertEquals("secret_key_missing", e.getStripeCode());
			assertFalse(e.isRetryable());
		}
	}
}
