package com.stripe.core.order.impl;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.constants.StripeCoreConstants;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.model.PaymentIntent;
import de.hybris.platform.core.model.order.AbstractOrderModel;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.PaymentService;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionEntryModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.i18n.I18NService;
import de.hybris.platform.servicelayer.model.ModelService;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripeOrderAuthorizationService}. It uses the same payment service call as the checkout strategy. The
 * merchant transaction code is the order code plus the PaymentIntent id, so it is the same on each retry.
 */
public class DefaultStripeOrderAuthorizationService implements StripeOrderAuthorizationService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeOrderAuthorizationService.class);

	private PaymentService paymentService;
	private StripeAmountConverter stripeAmountConverter;
	private I18NService i18nService;
	private ModelService modelService;

	@Override
	public boolean hasAcceptedAuthorization(final AbstractOrderModel order, final String paymentIntentId)
	{
		final Collection<PaymentTransactionModel> transactions = order.getPaymentTransactions();
		return transactions != null && transactions.stream()
				.map(PaymentTransactionModel::getEntries)
				.filter(Objects::nonNull)
				.flatMap(Collection::stream)
				.anyMatch(entry -> PaymentTransactionType.AUTHORIZATION.equals(entry.getType())
						&& TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus())
						&& paymentIntentId.equals(entry.getRequestId()));
	}

	@Override
	public boolean authorizePendingOrder(final OrderModel order, final PaymentIntent paymentIntent)
	{
		final String paymentIntentId = paymentIntent.getId();
		if (hasAcceptedAuthorization(order, paymentIntentId))
		{
			return true;
		}

		final BigDecimal amount = stripeAmountConverter.chargeAmount(order);
		final Currency currency = i18nService.getBestMatchingJavaCurrency(order.getCurrency().getIsocode());
		final PaymentTransactionEntryModel entry = paymentService.authorize(order.getCode() + "-" + paymentIntentId, amount,
				currency, order.getDeliveryAddress(), paymentIntentId, null, StripeCoreConstants.STRIPE_PROVIDER);
		final PaymentTransactionModel transaction = entry.getPaymentTransaction();

		if (TransactionStatus.ACCEPTED.name().equals(entry.getTransactionStatus()))
		{
			transaction.setOrder(order);
			transaction.setInfo(order.getPaymentInfo());
			modelService.save(transaction);
			LOG.info("Order {} is authorized by payment intent {}", order.getCode(), paymentIntentId);
			return true;
		}

		modelService.removeAll(List.of(transaction, entry));
		if (TransactionStatus.ERROR.name().equals(entry.getTransactionStatus()))
		{
			throw new IllegalStateException("Authorization of order " + order.getCode() + " by payment intent "
					+ paymentIntentId + " ended in error: " + entry.getTransactionStatusDetails());
		}
		LOG.warn("Authorization of order {} by payment intent {} was rejected: {}", order.getCode(), paymentIntentId,
				entry.getTransactionStatusDetails());
		return false;
	}

	public void setPaymentService(final PaymentService paymentService)
	{
		this.paymentService = paymentService;
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}

	public void setI18nService(final I18NService i18nService)
	{
		this.i18nService = i18nService;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}
}
