package com.stripe.core.customer;

import de.hybris.platform.core.model.order.CartModel;
import java.util.Optional;

/**
 * Gives the Stripe Customer of the user of a cart. A registered customer has one Stripe Customer per Stripe account,
 * stored as a {@code StripeCustomer}. A guest has one per cart, not stored.
 */
public interface StripeCustomerService
{
	/**
	 * Metadata key that marks a Stripe Customer created by this connector (value {@link #CONNECTOR_METADATA_VALUE}).
	 * The cash balance refund acts only on customers with this marker.
	 */
	String CONNECTOR_METADATA_KEY = "sap_commerce_connector";
	String CONNECTOR_METADATA_VALUE = "true";
	/** Metadata key of a guest Stripe Customer: the code of the cart it was created for. */
	String CART_CODE_METADATA_KEY = "cartCode";

	/**
	 * @return the Stripe Customer id, or empty when customer creation is disabled or the cart user is not a customer
	 */
	Optional<String> customerIdFor(CartModel cart);

	/**
	 * Replaces a Stripe Customer that Stripe reports as missing. Removes the stored {@code StripeCustomer} (if any) and
	 * creates a new Stripe Customer with a stable key that depends on the missing id.
	 *
	 * @return the id of the new Stripe Customer
	 */
	String replaceCustomer(CartModel cart, String missingStripeCustomerId);
}
