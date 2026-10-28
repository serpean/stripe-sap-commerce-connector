package com.stripe.core.payment;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.model.PaymentIntent;

/**
 * Copies the data of a PaymentIntent that the shop shows or needs later to its Stripe payment info: the payment method
 * (type, id, card brand, last 4 digits and expiry), the payment instructions of a voucher or a bank transfer
 * (reference, Stripe page, expiry, amount still to pay), the SEPA mandate and the date before which a reserved payment
 * must be captured. No full card number, IBAN or secret is ever copied.
 */
public interface StripePaymentDetailsCopier
{
	/**
	 * Copies the data that the PaymentIntent has and saves the payment info. Data that the PaymentIntent does not have
	 * stays as it is. The PaymentIntent must have payment_method and latest_charge expanded for the payment method and
	 * charge data.
	 */
	void copy(PaymentIntent paymentIntent, StripePaymentInfoModel paymentInfo);
}
