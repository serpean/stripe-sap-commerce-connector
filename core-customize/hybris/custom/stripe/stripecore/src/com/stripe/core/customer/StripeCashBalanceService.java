package com.stripe.core.customer;

/**
 * Money that a customer sent by bank transfer and Stripe holds as cash balance. Stripe applies the balance to an open
 * bank transfer PaymentIntent of the customer by itself. Balance that no open transfer can use has no SAP order, so it
 * is refunded to the customer.
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
	 * that Stripe can still apply the money to. Does nothing when the balance is zero. The refund key holds the amount,
	 * so a repeated call refunds the same balance only once.
	 */
	void refundUnappliedBalance(String customerId, String currency);
}
