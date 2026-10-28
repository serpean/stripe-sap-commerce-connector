package com.stripe.facades.order.converters.populators;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.facades.data.StripePaymentInfoData;
import de.hybris.platform.commercefacades.order.data.AbstractOrderData;
import de.hybris.platform.commercefacades.product.PriceDataFactory;
import de.hybris.platform.commercefacades.product.data.PriceData;
import de.hybris.platform.commercefacades.product.data.PriceDataType;
import de.hybris.platform.core.model.c2l.CurrencyModel;
import de.hybris.platform.converters.Populator;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * Sets {@link AbstractOrderData#getStripePaymentInfo()} for a cart or an order paid with Stripe: the payment method
 * and card data to show, the payment instructions of a voucher or bank transfer, and the payment status. The amount
 * still to pay is shown only while the payment is PENDING.
 */
public class StripeOrderPaymentPopulator implements Populator<AbstractOrderModel, AbstractOrderData>
{
	static final String ACCEPTED = "ACCEPTED";
	static final String PENDING = "PENDING";
	static final String FAILED = "FAILED";

	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private PriceDataFactory priceDataFactory;
	private StripeAmountConverter stripeAmountConverter;

	@Override
	public void populate(final AbstractOrderModel source, final AbstractOrderData target)
	{
		if (!(source.getPaymentInfo() instanceof StripePaymentInfoModel paymentInfo))
		{
			return;
		}
		final StripePaymentInfoData data = new StripePaymentInfoData();
		data.setPaymentMethodType(paymentInfo.getPaymentMethodType());
		data.setCardBrand(paymentInfo.getCardBrand());
		data.setCardLast4(paymentInfo.getCardLast4());
		data.setCardExpMonth(paymentInfo.getCardExpMonth());
		data.setCardExpYear(paymentInfo.getCardExpYear());
		data.setPaymentStatus(paymentStatus(source, paymentInfo));
		data.setInstructionsUrl(paymentInfo.getInstructionsUrl());
		data.setPaymentReference(paymentInfo.getPaymentReference());
		data.setInstructionsExpireAt(paymentInfo.getInstructionsExpireAt());
		if (PENDING.equals(data.getPaymentStatus()))
		{
			data.setAmountRemaining(formattedAmount(paymentInfo.getAmountRemaining(), source.getCurrency()));
		}
		target.setStripePaymentInfo(data);
	}

	/**
	 * ACCEPTED only for an ACCEPTED authorization of the current PaymentIntent of the payment info: an authorization of
	 * a released PaymentIntent does not count.
	 */
	private String paymentStatus(final AbstractOrderModel source, final StripePaymentInfoModel paymentInfo)
	{
		final String paymentIntentId = StringUtils.trimToNull(paymentInfo.getStripePaymentIntentId());
		if (paymentIntentId == null)
		{
			return null;
		}
		if (hasAcceptedAuthorization(source.getPaymentTransactions(), paymentIntentId))
		{
			return ACCEPTED;
		}
		return stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntentId)
				.map(StripePaymentIntentRecordModel::getState)
				.map(StripeOrderPaymentPopulator::statusOf)
				.orElse(null);
	}

	/**
	 * The amount in minor units, formatted in the currency of the order; null without amount or currency.
	 */
	private String formattedAmount(final Long minorUnits, final CurrencyModel currency)
	{
		if (minorUnits == null || currency == null)
		{
			return null;
		}
		final BigDecimal amount = stripeAmountConverter.fromMinorUnits(minorUnits, currency.getIsocode());
		final PriceData price = priceDataFactory.create(PriceDataType.BUY, amount, currency);
		return price == null ? null : price.getFormattedValue();
	}

	private static boolean hasAcceptedAuthorization(final Collection<PaymentTransactionModel> transactions,
			final String paymentIntentId)
	{
		return transactions != null && transactions.stream()
				.map(PaymentTransactionModel::getEntries)
				.filter(Objects::nonNull)
				.flatMap(Collection::stream)
				.anyMatch(entry -> PaymentTransactionType.AUTHORIZATION.equals(entry.getType())
						&& TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus())
						&& paymentIntentId.equals(entry.getRequestId()));
	}

	private static String statusOf(final StripePaymentIntentState state)
	{
		if (StripePaymentIntentState.PROCESSING.equals(state) || StripePaymentIntentState.AWAITING_FUNDS.equals(state))
		{
			return PENDING;
		}
		if (StripePaymentIntentState.FAILED.equals(state) || StripePaymentIntentState.CANCELED.equals(state))
		{
			return FAILED;
		}
		return null;
	}

	public void setStripePaymentIntentRecordDao(final StripePaymentIntentRecordDao stripePaymentIntentRecordDao)
	{
		this.stripePaymentIntentRecordDao = stripePaymentIntentRecordDao;
	}

	public void setPriceDataFactory(final PriceDataFactory priceDataFactory)
	{
		this.priceDataFactory = priceDataFactory;
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}
}
