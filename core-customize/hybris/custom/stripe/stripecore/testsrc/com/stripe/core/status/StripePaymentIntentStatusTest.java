package com.stripe.core.status;

import static org.junit.Assert.assertEquals;

import com.stripe.model.PaymentIntent;
import com.stripe.net.ApiResource;
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

	private static PaymentIntent intentWithAction(final String actionType)
	{
		final String json = "{\"id\":\"pi_1\",\"object\":\"payment_intent\",\"status\":\"requires_action\","
				+ "\"next_action\":{\"type\":\"" + actionType + "\"}}";
		return ApiResource.GSON.fromJson(json, PaymentIntent.class);
	}

	@Test
	public void multibancoVoucherIsAwaitingFunds()
	{
		assertEquals(StripePaymentIntentStatus.AWAITING_FUNDS,
				StripePaymentIntentStatus.of(intentWithAction("multibanco_display_details")));
	}

	@Test
	public void bankTransferInstructionsAreAwaitingFunds()
	{
		assertEquals(StripePaymentIntentStatus.AWAITING_FUNDS,
				StripePaymentIntentStatus.of(intentWithAction("display_bank_transfer_instructions")));
	}

	@Test
	public void threeDsIsStillRequiresAction()
	{
		assertEquals(StripePaymentIntentStatus.REQUIRES_ACTION, StripePaymentIntentStatus.of(intentWithAction("use_stripe_sdk")));
		assertEquals(StripePaymentIntentStatus.REQUIRES_ACTION,
				StripePaymentIntentStatus.of(intentWithAction("redirect_to_url")));
	}

	@Test
	public void intentWithoutNextActionUsesPlainMapping()
	{
		final PaymentIntent intent = ApiResource.GSON.fromJson("{\"id\":\"pi_2\",\"status\":\"requires_action\"}",
				PaymentIntent.class);
		assertEquals(StripePaymentIntentStatus.REQUIRES_ACTION, StripePaymentIntentStatus.of(intent));
	}
}
