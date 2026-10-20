package com.stripe.core.payment;

import static org.junit.Assert.assertEquals;

import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import de.hybris.bootstrap.annotations.UnitTest;
import org.junit.Test;

@UnitTest
public class StripePaymentIntentChecksTest
{
	private final StripePaymentIntentChecks checks = new StripePaymentIntentChecks();

	@Test
	public void netCapturedSubtractsTheRefundsOfTheLatestCharge()
	{
		final PaymentIntent paymentIntent = paymentIntent(10000L);
		final Charge charge = new Charge();
		charge.setAmountRefunded(2500L);
		paymentIntent.setLatestChargeObject(charge);

		assertEquals(7500L, checks.netCaptured(paymentIntent));
	}

	@Test
	public void netCapturedWithoutChargeIsTheAmountReceived()
	{
		assertEquals(10000L, checks.netCaptured(paymentIntent(10000L)));
	}

	@Test
	public void netCapturedWithoutAmountReceivedIsZero()
	{
		assertEquals(0L, checks.netCaptured(paymentIntent(null)));
	}

	private static PaymentIntent paymentIntent(final Long amountReceived)
	{
		final PaymentIntent paymentIntent = new PaymentIntent();
		paymentIntent.setId("pi_net");
		paymentIntent.setAmountReceived(amountReceived);
		return paymentIntent;
	}
}
