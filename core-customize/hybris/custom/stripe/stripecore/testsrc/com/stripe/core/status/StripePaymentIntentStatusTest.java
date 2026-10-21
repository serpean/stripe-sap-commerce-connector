package com.stripe.core.status;

import static org.junit.Assert.assertEquals;

import de.hybris.bootstrap.annotations.UnitTest;
import org.junit.Test;

@UnitTest
public class StripePaymentIntentStatusTest
{
	@Test
	public void mapsEveryKnownStatus()
	{
		assertEquals(StripePaymentIntentStatus.AUTHORIZED, StripePaymentIntentStatus.of("requires_capture"));
		assertEquals(StripePaymentIntentStatus.CAPTURED, StripePaymentIntentStatus.of("succeeded"));
		assertEquals(StripePaymentIntentStatus.PROCESSING, StripePaymentIntentStatus.of("processing"));
		assertEquals(StripePaymentIntentStatus.REQUIRES_ACTION, StripePaymentIntentStatus.of("requires_action"));
		assertEquals(StripePaymentIntentStatus.REQUIRES_ACTION, StripePaymentIntentStatus.of("requires_confirmation"));
		assertEquals(StripePaymentIntentStatus.FAILED, StripePaymentIntentStatus.of("requires_payment_method"));
		assertEquals(StripePaymentIntentStatus.CANCELED, StripePaymentIntentStatus.of("canceled"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void unknownStatusFails()
	{
		StripePaymentIntentStatus.of("something_new");
	}
}
