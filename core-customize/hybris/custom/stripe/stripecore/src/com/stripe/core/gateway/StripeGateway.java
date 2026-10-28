package com.stripe.core.gateway;

import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.CustomerUpdateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentUpdateParams;
import java.util.List;
import java.util.Map;

/**
 * The only door to the Stripe API. Each method throws {@link StripeGatewayException} on error.
 * Calls that create or move money need a stable idempotency key.
 */
public interface StripeGateway
{
	PaymentIntent createPaymentIntent(PaymentIntentCreateParams params, String idempotencyKey);

	PaymentIntent updatePaymentIntent(String id, PaymentIntentUpdateParams params, String idempotencyKey);

	/**
	 * Gets the PaymentIntent with {@code payment_method}, {@code latest_charge} and {@code amount_details.line_items}
	 * expanded. stripe-java has no field for the line items: read them from the raw JSON of the PaymentIntent.
	 */
	PaymentIntent retrievePaymentIntent(String id);

	PaymentIntent cancelPaymentIntent(String id, String idempotencyKey);

	PaymentIntent capturePaymentIntent(String id, long amountToCapture, String idempotencyKey);

	/**
	 * Captures with new {@code amount_details}. Stripe needs them when the PaymentIntent has line items and less than
	 * its amount is captured: the line items must add up to the amount to capture.
	 *
	 * @param amountDetails
	 *           the {@code amount_details} hash in nested form; empty sends none
	 */
	PaymentIntent capturePaymentIntent(String id, long amountToCapture, Map<String, Object> amountDetails,
			String idempotencyKey);

	Refund createRefund(String paymentIntentId, long amount, String idempotencyKey);

	Customer createCustomer(CustomerCreateParams params, String idempotencyKey);

	Customer updateCustomer(String id, CustomerUpdateParams params, String idempotencyKey);

	/**
	 * The Stripe Customer with its metadata. A deleted customer comes back with {@code deleted=true}.
	 */
	Customer retrieveCustomer(String id);

	/**
	 * The available cash balance of the customer in this currency, in minor units (0 when there is none).
	 */
	long retrieveCashBalance(String customerId, String currency);

	/**
	 * The id of the newest cash balance transaction of the customer, or null when there is none. The id names the
	 * funds of the balance: new funds or a returned payment give a new id.
	 */
	String retrieveLatestCashBalanceTransactionId(String customerId);

	/**
	 * Refunds the customer cash balance to the customer ({@code origin=customer_balance}). Stripe asks the customer for
	 * bank details by email.
	 */
	Refund createCashBalanceRefund(String customerId, long amount, String currency, String idempotencyKey);

	/**
	 * The PaymentIntents of the customer with status requires_action, requires_confirmation or processing. Stripe lists
	 * the newest 100 PaymentIntents of the customer; the gateway filters them by status.
	 */
	List<PaymentIntent> listOpenPaymentIntents(String customerId);

	/**
	 * The id of the Stripe account of the secret key plus {@code ":live"} or {@code ":test"}. The gateway asks Stripe
	 * once per secret key and keeps the answer in memory.
	 */
	String retrieveAccountKey();

	/**
	 * Checks the signature and builds the event.
	 *
	 * @throws StripeGatewayException with {@code isSignatureInvalid()} when the signature is wrong
	 */
	Event constructEvent(String payload, String signatureHeader);
}
