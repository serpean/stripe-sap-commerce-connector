package com.stripe.core.customer;

/**
 * Money that a customer sent by bank transfer and Stripe holds as cash balance. Stripe applies the balance to an open
 * bank transfer PaymentIntent of the customer by itself. Balance that no open transfer can use has no SAP order, so it
 * is refunded to the customer. Only Stripe customers of this installation are checked: a customer needs the connector
 * metadata marker and a local row (a {@code StripeCustomer}, or a PaymentIntent record of its guest cart). Other
 * customers of the Stripe account are skipped.
 */
public interface StripeCashBalanceService
{
	/**
	 * Plans a check of the balance after the configured delay. There is one pending check for each customer and
	 * currency: a second call while a check is pending does nothing.
	 */
	void scheduleRefundCheck(String customerId, String currency);

	/**
	 * Refunds the available balance of the customer in this currency, unless the customer has an open bank transfer
	 * that Stripe can still apply the money to in this currency. Does nothing when the balance is zero. When the balance is
	 * kept, a new check is planned: Stripe sends no event when a cancelled transfer returns money to the balance. The
	 * refund key holds the latest cash balance transaction and the amount, so a repeated call refunds the same funds only
	 * once and new funds of the same amount get a new key.
	 */
	void refundUnappliedBalance(String customerId, String currency);
}
