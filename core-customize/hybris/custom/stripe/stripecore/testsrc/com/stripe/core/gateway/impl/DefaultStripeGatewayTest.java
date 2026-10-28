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
import com.stripe.exception.CardException;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.InvalidRequestException;
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
	@Test
	public void idempotencyKeyInUseIsRetryable()
	{
		assertTrue("409: another request with the same key is still running",
				gateway.isRetryable(new IdempotencyException("in use", "req_1", "idempotency_key_in_use", 409)));
		assertTrue("409 without a code",
				gateway.isRetryable(new InvalidRequestException("conflict", null, "req_2", null, 409, null)));
	}

	@Test
	public void lockTimeoutIsRetryable()
	{
		assertTrue(gateway.isRetryable(new InvalidRequestException("locked", null, "req_3", "lock_timeout", 400, null)));
	}

	@Test
	public void requestErrorsAreNotRetryable()
	{
		assertFalse("other parameters with the same key",
				gateway.isRetryable(new IdempotencyException("mismatch", "req_4", "idempotency_error", 400)));
		assertFalse(gateway.isRetryable(new CardException("declined", "req_5", "card_declined", null, null, null, 402, null)));
	}

	@Test
	public void accountKeyIsAskedOncePerSecretKeyAndMarksMode()
	{
		final int[] calls = { 0 };
		final DefaultStripeGateway counting = new DefaultStripeGateway()
		{
			@Override
			@SuppressWarnings("unchecked")
			protected <T> T call(final String operation, final StripeCall<T> stripeCall)
			{
				calls[0]++;
				final com.stripe.model.Account account = new com.stripe.model.Account();
				account.setId("acct_1");
				return (T) account;
			}
		};
		counting.setStripeConfigurationService(configuration);
		when(configuration.getSecretKey()).thenReturn("sk_test_abc");

		assertEquals("acct_1:test", counting.retrieveAccountKey());
		assertEquals("acct_1:test", counting.retrieveAccountKey());
		assertEquals(1, calls[0]);

		when(configuration.getSecretKey()).thenReturn("rk_live_abc");
		assertEquals("acct_1:live", counting.retrieveAccountKey());
		assertEquals(2, calls[0]);
	}

	@Test
	public void accountKeyNeedsSecretKey()
	{
		when(configuration.getSecretKey()).thenReturn("");
		try
		{
			gateway.retrieveAccountKey();
			fail("A missing key must throw");
		}
		catch (final StripeGatewayException e)
		{
			assertEquals("secret_key_missing", e.getStripeCode());
		}
	}
}
