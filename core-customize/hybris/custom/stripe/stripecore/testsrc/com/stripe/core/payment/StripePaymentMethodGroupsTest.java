package com.stripe.core.payment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.UnitTest;
import org.junit.Test;

@UnitTest
public class StripePaymentMethodGroupsTest
{
	@Test
	public void groups()
	{
		assertTrue(StripePaymentMethodGroups.isDeferred("multibanco"));
		assertTrue(StripePaymentMethodGroups.isDeferred("customer_balance"));
		assertFalse(StripePaymentMethodGroups.isDeferred("sepa_debit"));
		assertFalse(StripePaymentMethodGroups.isDeferred(null));

		assertTrue(StripePaymentMethodGroups.isInstructionsAction("multibanco_display_details"));
		assertTrue(StripePaymentMethodGroups.isInstructionsAction("display_bank_transfer_instructions"));
		assertFalse(StripePaymentMethodGroups.isInstructionsAction("redirect_to_url"));
		assertFalse(StripePaymentMethodGroups.isInstructionsAction("use_stripe_sdk"));
		assertFalse(StripePaymentMethodGroups.isInstructionsAction(null));
	}
}
