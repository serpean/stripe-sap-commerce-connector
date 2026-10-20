package com.stripe.core.payment.impl;

import com.stripe.core.payment.StripeTransactionLookup;
import de.hybris.platform.payment.dto.TransactionStatus;
import de.hybris.platform.payment.enums.PaymentTransactionType;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * FlexibleSearch implementation of {@link StripeTransactionLookup}.
 */
public class DefaultStripeTransactionLookup implements StripeTransactionLookup
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeTransactionLookup.class);

	private static final String LIVE_TRANSACTION_BY_AUTHORIZATION = "SELECT DISTINCT {t.pk} FROM {PaymentTransactionEntry AS e"
			+ " JOIN PaymentTransaction AS t ON {e.paymentTransaction} = {t.pk}"
			+ " JOIN Order AS o ON {t.order} = {o.pk}}"
			+ " WHERE {e.requestId} = ?requestId AND {e.type} = ?type AND {o.versionID} IS NULL";
	private static final String COUNT_SETTLED_REFUNDS = "SELECT COUNT({pk}) FROM {PaymentTransactionEntry}"
			+ " WHERE {paymentTransaction} = ?transaction AND {type} = ?type AND {transactionStatus} IN (?statuses)";

	private FlexibleSearchService flexibleSearchService;

	@Override
	public Optional<PaymentTransactionModel> findByPaymentIntentId(final String paymentIntentId)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(LIVE_TRANSACTION_BY_AUTHORIZATION);
		query.addQueryParameter("requestId", paymentIntentId);
		query.addQueryParameter("type", PaymentTransactionType.AUTHORIZATION);
		final List<PaymentTransactionModel> transactions = flexibleSearchService.<PaymentTransactionModel> search(query)
				.getResult();
		if (transactions.size() > 1)
		{
			LOG.warn("PaymentIntent [{}] belongs to {} live payment transactions", paymentIntentId, transactions.size());
		}
		return transactions.size() == 1 ? Optional.of(transactions.get(0)) : Optional.empty();
	}

	@Override
	public int countSettledRefunds(final PaymentTransactionModel transaction)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(COUNT_SETTLED_REFUNDS);
		query.addQueryParameter("transaction", transaction);
		query.addQueryParameter("type", PaymentTransactionType.REFUND_FOLLOW_ON);
		query.addQueryParameter("statuses", List.of(TransactionStatus.ACCEPTED.name(), TransactionStatus.REJECTED.name()));
		query.setResultClassList(List.of(Integer.class));
		return flexibleSearchService.<Integer> search(query).getResult().get(0);
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}
}
