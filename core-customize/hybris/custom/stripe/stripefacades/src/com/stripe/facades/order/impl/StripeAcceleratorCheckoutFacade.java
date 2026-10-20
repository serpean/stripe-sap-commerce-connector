package com.stripe.facades.order.impl;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.order.StripeOrderPlacementLockService;
import com.stripe.facades.checkout.StripeCheckoutAuthorizer;
import de.hybris.platform.acceleratorfacades.order.impl.DefaultAcceleratorCheckoutFacade;
import de.hybris.platform.commercefacades.order.data.OrderData;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.order.InvalidCartException;
import org.apache.commons.lang3.StringUtils;

/**
 * {@link DefaultAcceleratorCheckoutFacade} that authorizes a cart with a Stripe payment info through the
 * {@link StripeCheckoutAuthorizer}, and gives back the placement lock when the order is not placed. Other payment
 * infos use the standard SAP behavior.
 */
public class StripeAcceleratorCheckoutFacade extends DefaultAcceleratorCheckoutFacade
{
	private StripeCheckoutAuthorizer stripeCheckoutAuthorizer;
	private StripeOrderPlacementLockService stripeOrderPlacementLockService;

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
	 * Places the order of a Stripe cart. When no order is placed, gives back the placement lock of the PaymentIntent, so
	 * that the customer can try again at once.
	 */
	@Override
	public OrderData placeOrder() throws InvalidCartException
	{
		final String paymentIntentId = stripePaymentIntentId(getCart());
		if (paymentIntentId == null)
		{
			return super.placeOrder();
		}
		boolean placed = false;
		try
		{
			final OrderData order = super.placeOrder();
			placed = order != null;
			return order;
		}
		finally
		{
			if (!placed)
			{
				stripeOrderPlacementLockService.release(paymentIntentId);
			}
		}
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

	public void setStripeOrderPlacementLockService(final StripeOrderPlacementLockService stripeOrderPlacementLockService)
	{
		this.stripeOrderPlacementLockService = stripeOrderPlacementLockService;
	}
}
