package com.stripe.core.order;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.constants.StripeCoreConstants;
import com.stripe.core.model.StripePaymentInfoModel;
import de.hybris.platform.commerceservices.service.data.CommerceCheckoutParameter;
import de.hybris.platform.commerceservices.order.impl.DefaultCommercePaymentAuthorizationStrategy;
import de.hybris.platform.core.model.order.CartModel;
import de.hybris.platform.core.model.order.payment.PaymentInfoModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Required;

/**
 * Authorizes the cart of a Stripe payment info against its PaymentIntent. The amount is always the Stripe charge
 * amount of the cart. Other payment infos use the standard SAP behavior.
 */
public class StripeCommercePaymentAuthorizationStrategy extends DefaultCommercePaymentAuthorizationStrategy
{
	private StripeAmountConverter stripeAmountConverter;

	@Override
	public PaymentTransactionEntryModel authorizePaymentAmount(final CommerceCheckoutParameter parameter)
	{
		final CartModel cart = parameter.getCart();
		final PaymentInfoModel paymentInfo = cart.getPaymentInfo();
		if (!(paymentInfo instanceof StripePaymentInfoModel))
		{
			return super.authorizePaymentAmount(parameter);
		}

		final StripePaymentInfoModel stripePaymentInfo = (StripePaymentInfoModel) paymentInfo;
		PaymentTransactionEntryModel entry = null;
		try
		{
			beforeAuthorizePaymentAmount(parameter);
			if (StringUtils.isNotBlank(stripePaymentInfo.getStripePaymentIntentId()))
			{
				entry = authorize(cart, stripePaymentInfo);
				finishTransaction(cart, paymentInfo, entry);
			}
			return entry;
		}
		finally
		{
			afterAuthorizePaymentAmount(parameter, entry);
		}
	}

	private PaymentTransactionEntryModel authorize(final CartModel cart, final StripePaymentInfoModel paymentInfo)
	{
		final BigDecimal amount = stripeAmountConverter.chargeAmount(cart);
		final Currency currency = getI18nService().getBestMatchingJavaCurrency(cart.getCurrency().getIsocode());
		final String merchantTransactionCode = getGenerateMerchantTransactionCodeStrategy().generateCode(cart);
		return getPaymentService().authorize(merchantTransactionCode, amount, currency, cart.getDeliveryAddress(),
				paymentInfo.getStripePaymentIntentId(), null, StripeCoreConstants.STRIPE_PROVIDER);
	}

	private void finishTransaction(final CartModel cart, final PaymentInfoModel paymentInfo,
			final PaymentTransactionEntryModel entry)
	{
		if (entry == null)
		{
			return;
		}
		final PaymentTransactionModel transaction = entry.getPaymentTransaction();
		if (TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus()))
		{
			transaction.setOrder(cart);
			transaction.setInfo(paymentInfo);
			getModelService().saveAll(cart, transaction);
		}
		else
		{
			getModelService().removeAll(Arrays.asList(transaction, entry));
		}
	}

	@Required
	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}
}
