package com.stripe.facades.order.impl;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.facades.checkout.StripeCheckoutAuthorizer;
import com.stripe.facades.order.StripePlaceOrderGuard;
import de.hybris.platform.commercefacades.order.impl.DefaultCheckoutFacade;
import de.hybris.platform.commercefacades.order.data.OrderData;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.order.InvalidCartException;
import org.apache.commons.lang3.StringUtils;

/**
 * {@link DefaultCheckoutFacade} that authorizes a cart with a Stripe payment info through the
 * {@link StripeCheckoutAuthorizer}, and places its order through the {@link StripePlaceOrderGuard}. Other payment infos
 * use the standard SAP behavior.
 */
public class StripeCheckoutFacade extends DefaultCheckoutFacade
{
	private StripeCheckoutAuthorizer stripeCheckoutAuthorizer;
	private StripePlaceOrderGuard stripePlaceOrderGuard;

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

	/**
	 * Places the order of a Stripe cart through the {@link StripePlaceOrderGuard}.
	 */
	@Override
	public OrderData placeOrder() throws InvalidCartException
	{
		final String paymentIntentId = stripePaymentIntentId(getCart());
		if (paymentIntentId == null)
		{
			return super.placeOrder();
		}
		return stripePlaceOrderGuard.placeOrder(paymentIntentId, super::placeOrder);
	}

	private String stripePaymentIntentId(final CartModel cart)
	{
		if (cart != null && cart.getPaymentInfo() instanceof StripePaymentInfoModel)
		{
			return StringUtils.trimToNull(((StripePaymentInfoModel) cart.getPaymentInfo()).getStripePaymentIntentId());
		}
		return null;
	}

	public void setStripeCheckoutAuthorizer(final StripeCheckoutAuthorizer stripeCheckoutAuthorizer)
	{
		this.stripeCheckoutAuthorizer = stripeCheckoutAuthorizer;
	}

	public void setStripePlaceOrderGuard(final StripePlaceOrderGuard stripePlaceOrderGuard)
	{
		this.stripePlaceOrderGuard = stripePlaceOrderGuard;
	}
}
