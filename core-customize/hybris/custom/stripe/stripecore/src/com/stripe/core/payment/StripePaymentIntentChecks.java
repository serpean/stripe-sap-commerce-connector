package com.stripe.core.payment;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.status.StripePaymentIntentStatus;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import java.util.Objects;

/**
 * The money checks of a PaymentIntent against a cart or an order. Every placement and authorization path uses these
 * checks, so there is one rule for each.
 */
public class StripePaymentIntentChecks
{
	private StripeAmountConverter stripeAmountConverter;

	/**
	 * @return true when the amount and the currency of the PaymentIntent are the Stripe charge amount and the currency
	 *         of the cart or order
	 */
	public boolean matchesAmount(final AbstractOrderModel order, final PaymentIntent paymentIntent)
	{
		return paymentIntent.getAmount() != null && order.getCurrency() != null
				&& paymentIntent.getAmount() == stripeAmountConverter.chargeAmountMinor(order)
				&& order.getCurrency().getIsocode().equalsIgnoreCase(paymentIntent.getCurrency());
	}

	/**
	 * A captured PaymentIntent holds all its money when Stripe received the full amount and its expanded latest charge
	 * has no refund. Without the charge details the answer is false: the check fails closed. A PaymentIntent that is not
	 * captured has no captured money to lose, so the answer is true.
	 */
	public boolean holdsAllMoney(final PaymentIntent paymentIntent)
	{
		if (paymentIntent.getStatus() == null
				|| StripePaymentIntentStatus.of(paymentIntent.getStatus()) != StripePaymentIntentStatus.CAPTURED)
		{
			return true;
		}
		final Charge charge = paymentIntent.getLatestChargeObject();
		return paymentIntent.getAmount() != null
				&& Objects.equals(paymentIntent.getAmount(), paymentIntent.getAmountReceived())
				&& charge != null && charge.getAmountRefunded() != null && charge.getAmountRefunded() == 0L;
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}
}
