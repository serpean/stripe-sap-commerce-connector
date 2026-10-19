package com.stripe.facades.order.impl;

import com.stripe.core.stripe.StripePaymentProvider;
import de.hybris.platform.commercefacades.order.impl.DefaultCheckoutFacade;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.stripe.core.constants.StripeCoreConstants.STRIPE_PROVIDER;

/**
 * Stripe-specific checkout facade that handles payment authorization via Stripe.
 */
public class StripeCheckoutFacade extends DefaultCheckoutFacade {

    private static final Logger LOG = LoggerFactory.getLogger(StripeCheckoutFacade.class);

    private StripePaymentProvider stripePaymentProvider;

    @Override
    public boolean authorizePayment(final String securityCode) {
        final String paymentProvider = getPaymentProvider();

        // Only handle Stripe payments, delegate others to parent
        if (!isStripePayment(paymentProvider)) {
            return super.authorizePayment(securityCode);
        }

        LOG.debug("Processing Stripe authorization");

        final CartModel cart = getCart();
        if (cart == null) {
            LOG.error("No cart found in session for Stripe authorization");
            return false;
        }

        // Check if we already have an accepted authorization
        if (stripePaymentProvider.hasAcceptedAuthorization(cart)) {
            LOG.info("Stripe authorization already accepted for cart: {}", cart.getCode());
            return true;
        }

        // No PaymentIntent exists - create one via StripePaymentProvider
        LOG.info("No PaymentIntent found for cart: {}, creating authorization", cart.getCode());
        return createAuthorization(cart);
    }

    /**
     * Checks if the payment provider is Stripe.
     *
     * @param paymentProvider the payment provider name
     * @return true if Stripe
     */
    protected boolean isStripePayment(final String paymentProvider) {
        return StringUtils.equalsIgnoreCase(paymentProvider, STRIPE_PROVIDER);
    }

    /**
     * Creates a new authorization (PaymentIntent) for the cart.
     *
     * @param cart the cart
     * @return true if authorization was created successfully
     */
    protected boolean createAuthorization(final CartModel cart) {
        try {
            final PaymentTransactionEntryModel entry = stripePaymentProvider.authorize(cart);

            if (entry == null) {
                LOG.error("Failed to create authorization for cart: {}", cart.getCode());
                return false;
            }

            final String status = entry.getTransactionStatus();
            LOG.info("Authorization created for cart {}: status={}, intentId={}",
                    cart.getCode(), status, entry.getRequestId());

            // For Stripe, authorization creates a PaymentIntent in "requires_payment_method" or "requires_confirmation"
            // The frontend still needs to confirm with card details
            // We return true to allow checkout to proceed to the payment confirmation step
            return TransactionStatus.ACCEPTED.name().equals(status)
                    || TransactionStatus.REVIEW.name().equals(status);

        } catch (final Exception e) {
            LOG.error("Exception creating authorization for cart: {}", cart.getCode(), e);
            return false;
        }
    }

    // Setter for Spring injection

    public void setStripePaymentProvider(final StripePaymentProvider stripePaymentProvider) {
        this.stripePaymentProvider = stripePaymentProvider;
    }
}
