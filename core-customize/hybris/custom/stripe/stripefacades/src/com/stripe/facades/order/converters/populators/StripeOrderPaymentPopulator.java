package com.stripe.facades.order.converters.populators;

import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.model.StripePaymentInfoModel;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.facades.data.StripePaymentInfoData;
import de.hybris.platform.commercefacades.order.data.AbstractOrderData;
import de.hybris.platform.converters.Populator;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import java.util.Collection;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * Sets {@link AbstractOrderData#getStripePaymentInfo()} for a cart or an order paid with Stripe: the payment method
 * and card data to show, and the payment status.
 */
public class StripeOrderPaymentPopulator implements Populator<AbstractOrderModel, AbstractOrderData>
{
	static final String ACCEPTED = "ACCEPTED";
	static final String PENDING = "PENDING";
	static final String FAILED = "FAILED";

	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;

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
		target.setStripePaymentInfo(data);
	}

	private String paymentStatus(final AbstractOrderModel source, final StripePaymentInfoModel paymentInfo)
	{
		if (hasAcceptedAuthorization(source.getPaymentTransactions()))
		{
			return ACCEPTED;
		}
		final String paymentIntentId = StringUtils.trimToNull(paymentInfo.getStripePaymentIntentId());
		if (paymentIntentId == null)
		{
			return null;
		}
		return stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntentId)
				.map(StripePaymentIntentRecordModel::getState)
				.map(StripeOrderPaymentPopulator::statusOf)
				.orElse(null);
	}

	private static boolean hasAcceptedAuthorization(final Collection<PaymentTransactionModel> transactions)
	{
		return transactions != null && transactions.stream()
				.map(PaymentTransactionModel::getEntries)
				.filter(Objects::nonNull)
				.flatMap(Collection::stream)
				.anyMatch(entry -> PaymentTransactionType.AUTHORIZATION.equals(entry.getType())
						&& TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus()));
	}

	private static String statusOf(final StripePaymentIntentState state)
	{
		if (StripePaymentIntentState.PROCESSING.equals(state))
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
}
