package com.stripe.facades.order.impl;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.facades.checkout.StripeCheckoutAuthorizer;
import de.hybris.platform.commercefacades.order.impl.DefaultCheckoutFacade;
import de.hybris.platform.core.model.order.CartModel;

/**
 * {@link DefaultCheckoutFacade} that authorizes a cart with a Stripe payment info through the
 * {@link StripeCheckoutAuthorizer}. Other payment infos use the standard SAP behavior.
 */
public class StripeCheckoutFacade extends DefaultCheckoutFacade
{
	private StripeCheckoutAuthorizer stripeCheckoutAuthorizer;

	@Override
	public boolean authorizePayment(final String securityCode)
	{
		final CartModel cart = getCart();
		if (cart != null && cart.getPaymentInfo() instanceof StripePaymentInfoModel)
		{
			return checkIfCurrentUserIsTheCartUser() && stripeCheckoutAuthorizer.authorize(cart);
		}
		return super.authorizePayment(securityCode);
	}

	public void setStripeCheckoutAuthorizer(final StripeCheckoutAuthorizer stripeCheckoutAuthorizer)
	{
		this.stripeCheckoutAuthorizer = stripeCheckoutAuthorizer;
	}
}
