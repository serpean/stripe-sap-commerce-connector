package com.stripe.core.payment;

import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;

import java.util.List;
import java.util.Optional;

/**
 * Finds the SAP payment transaction that owns a Stripe PaymentIntent.
 */
public interface StripeTransactionLookup
{
	/**
	 * The transaction of a live order (not an order history snapshot) whose AUTHORIZATION entry has the PaymentIntent id
	 * as requestId. Empty when no live transaction or more than one live transaction matches.
	 */
	Optional<PaymentTransactionModel> findByPaymentIntentId(String paymentIntentId);

	/**
	 * Number of follow-on refunds of the transaction that have a final answer from Stripe: ACCEPTED or REJECTED. Reads
	 * the database, not the cached entries of the model.
	 */
	int countSettledRefunds(PaymentTransactionModel transaction);

	/**
	 * The REFUND_FOLLOW_ON entries of live orders (not order history snapshots) whose requestId is the refund id.
	 */
	List<PaymentTransactionEntryModel> findRefundEntries(String refundId);
}
