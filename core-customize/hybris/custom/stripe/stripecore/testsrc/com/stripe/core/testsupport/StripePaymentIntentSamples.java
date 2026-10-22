package com.stripe.core.testsupport;

import com.stripe.model.PaymentIntent;
import com.stripe.net.ApiResource;
import com.stripe.net.HttpHeaders;
import com.stripe.net.StripeResponse;
import java.util.Map;

/**
 * Sample PaymentIntents as Stripe sends them (test mode data, shape of the Stripe API), read with the stripe-java
 * deserializer.
 */
public final class StripePaymentIntentSamples
{
	/** 2026-10-29T12:00:00Z in epoch seconds. */
	public static final long EXPIRES_AT = 1793275200L;
	/** 2026-10-28T12:00:00Z in epoch seconds. */
	public static final long CAPTURE_BEFORE = 1793188800L;

	public static final String MULTIBANCO = """
			{
			  "id": "pi_mb",
			  "object": "payment_intent",
			  "amount": 12100,
			  "currency": "eur",
			  "status": "requires_action",
			  "payment_method": {
			    "id": "pm_mb",
			    "object": "payment_method",
			    "type": "multibanco",
			    "multibanco": {}
			  },
			  "next_action": {
			    "type": "multibanco_display_details",
			    "multibanco_display_details": {
			      "entity": "12345",
			      "reference": "123456789",
			      "expires_at": 1793275200,
			      "hosted_voucher_url": "https://payments.stripe.com/multibanco/voucher/test_YWNjdF8x"
			    }
			  }
			}
			""";

	public static final String BANK_TRANSFER = """
			{
			  "id": "pi_bt",
			  "object": "payment_intent",
			  "amount": 12100,
			  "currency": "eur",
			  "status": "requires_action",
			  "customer": "cus_1",
			  "payment_method": {
			    "id": "pm_bt",
			    "object": "payment_method",
			    "type": "customer_balance",
			    "customer_balance": {}
			  },
			  "next_action": {
			    "type": "display_bank_transfer_instructions",
			    "display_bank_transfer_instructions": {
			      "amount_remaining": 5100,
			      "currency": "eur",
			      "financial_addresses": [
			        {
			          "type": "iban",
			          "iban": {
			            "account_holder_name": "Shop",
			            "bic": "SXPYDEHH",
			            "country": "DE",
			            "iban": "DE00000000000000000000"
			          },
			          "supported_networks": ["sepa"]
			        }
			      ],
			      "hosted_instructions_url": "https://payments.stripe.com/bank_transfers/instructions/test_YWNjdF8x",
			      "reference": "QHJ7RZ2MXN4P",
			      "type": "eu_bank_transfer"
			    }
			  }
			}
			""";

	public static final String SEPA_DEBIT = """
			{
			  "id": "pi_sepa",
			  "object": "payment_intent",
			  "amount": 12100,
			  "currency": "eur",
			  "status": "processing",
			  "payment_method": {
			    "id": "pm_sepa",
			    "object": "payment_method",
			    "type": "sepa_debit",
			    "sepa_debit": {"last4": "3000", "country": "AT"}
			  },
			  "latest_charge": {
			    "id": "ch_sepa",
			    "object": "charge",
			    "payment_method_details": {
			      "type": "sepa_debit",
			      "sepa_debit": {"last4": "3000", "country": "AT", "mandate": "mandate_1Abc"}
			    }
			  }
			}
			""";

	public static final String CARD_AUTHORIZED = """
			{
			  "id": "pi_card",
			  "object": "payment_intent",
			  "amount": 12100,
			  "currency": "eur",
			  "status": "requires_capture",
			  "payment_method": {
			    "id": "pm_card",
			    "object": "payment_method",
			    "type": "card",
			    "card": {"brand": "visa", "last4": "4242", "exp_month": 12, "exp_year": 2030}
			  },
			  "latest_charge": {
			    "id": "ch_card",
			    "object": "charge",
			    "payment_method_details": {
			      "type": "card",
			      "card": {"brand": "visa", "last4": "4242", "exp_month": 12, "exp_year": 2030,
			        "capture_before": 1793188800}
			    }
			  }
			}
			""";

	public static final String PAYPAL_AUTHORIZED = """
			{
			  "id": "pi_paypal",
			  "object": "payment_intent",
			  "amount": 12100,
			  "currency": "eur",
			  "status": "requires_capture",
			  "payment_method": {
			    "id": "pm_paypal",
			    "object": "payment_method",
			    "type": "paypal",
			    "paypal": {}
			  },
			  "latest_charge": {
			    "id": "ch_paypal",
			    "object": "charge",
			    "payment_method_details": {
			      "type": "paypal",
			      "paypal": {"capture_before": 1793188800}
			    }
			  }
			}
			""";

	private StripePaymentIntentSamples()
	{
		// constants only
	}

	/**
	 * The PaymentIntent of the JSON, as the event deserializer gives it (no raw JSON).
	 */
	public static PaymentIntent paymentIntent(final String json)
	{
		return ApiResource.GSON.fromJson(json, PaymentIntent.class);
	}

	/**
	 * The PaymentIntent of the JSON, as an API call gives it: its raw JSON comes from the response body.
	 */
	public static PaymentIntent retrievedPaymentIntent(final String json)
	{
		final PaymentIntent paymentIntent = paymentIntent(json);
		paymentIntent.setLastResponse(new StripeResponse(200, HttpHeaders.of(Map.of()), json));
		return paymentIntent;
	}
}
