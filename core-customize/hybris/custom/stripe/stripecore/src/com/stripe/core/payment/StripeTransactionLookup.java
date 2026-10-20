package com.stripe.core.payment;

import de.hybris.platform.payment.model.PaymentTransactionModel;

import java.util.Optional;

/**
 * Finds the SAP payment transaction that owns a Stripe PaymentIntent.
 */
public interface StripeTransactionLookup
{
	/**
	 * The transaction whose AUTHORIZATION entry has the PaymentIntent id as requestId, or empty when there is none.
	 */
	Optional<PaymentTransactionModel> findByPaymentIntentId(String paymentIntentId);
}
