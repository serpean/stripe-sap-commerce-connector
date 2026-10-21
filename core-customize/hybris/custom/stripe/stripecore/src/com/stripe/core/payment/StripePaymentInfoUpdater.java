package com.stripe.core.payment;

import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import de.hybris.platform.servicelayer.model.ModelService;

/**
 * Copies the display data of the payment method of a PaymentIntent to its Stripe payment info: type, id and, for a
 * card, brand, last 4 digits and expiry. The PaymentIntent must have its payment_method expanded; without it, nothing
 * changes. No full card number or secret is ever available or copied.
 */
public class StripePaymentInfoUpdater
{
	private ModelService modelService;

	public void copyPaymentMethod(final StripePaymentInfoModel paymentInfo, final PaymentIntent paymentIntent)
	{
		final PaymentMethod method = paymentIntent.getPaymentMethodObject();
		if (method == null)
		{
			return;
		}
		paymentInfo.setPaymentMethodType(method.getType());
		paymentInfo.setPaymentMethodId(method.getId());
		final PaymentMethod.Card card = method.getCard();
		if (card != null)
		{
			paymentInfo.setCardBrand(card.getBrand());
			paymentInfo.setCardLast4(card.getLast4());
			paymentInfo.setCardExpMonth(card.getExpMonth() == null ? null : Math.toIntExact(card.getExpMonth()));
			paymentInfo.setCardExpYear(card.getExpYear() == null ? null : Math.toIntExact(card.getExpYear()));
		}
		modelService.save(paymentInfo);
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}
}
