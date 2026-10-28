package com.stripe.core.payment;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.model.PaymentIntent;

/**
 * Copies the display data of a PaymentIntent to its Stripe payment info.
 *
 * @deprecated since 4.0.0, use {@link StripePaymentDetailsCopier} (bean {@code stripePaymentDetailsCopier}). This class
 *             only calls it and stays for compatibility with 3.0.0.
 */
@Deprecated(since = "4.0.0")
public class StripePaymentInfoUpdater
{
	private StripePaymentDetailsCopier stripePaymentDetailsCopier;

	public void copyPaymentMethod(final StripePaymentInfoModel paymentInfo, final PaymentIntent paymentIntent)
	{
		stripePaymentDetailsCopier.copy(paymentIntent, paymentInfo);
	}

	/**
	 * Not used any more: the copier saves the payment info. Kept so that 3.0.0 bean definitions still load.
	 */
	public void setModelService(final de.hybris.platform.servicelayer.model.ModelService modelService)
	{
		// the copier saves the payment info
	}

	public void setStripePaymentDetailsCopier(final StripePaymentDetailsCopier stripePaymentDetailsCopier)
	{
		this.stripePaymentDetailsCopier = stripePaymentDetailsCopier;
	}
}
