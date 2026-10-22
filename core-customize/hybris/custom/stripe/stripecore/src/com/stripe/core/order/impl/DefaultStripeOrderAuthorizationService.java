package com.stripe.core.order.impl;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.constants.StripeCoreConstants;
import com.stripe.core.order.StripeOrderAuthorizationService;
import com.stripe.core.order.StripeOrderPlacementLockService;
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
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link StripeOrderAuthorizationService}. It uses the same payment service call as the checkout strategy. The
 * merchant transaction code is the order code plus the PaymentIntent id, so it is the same on each retry.
 * <p>
 * A webhook task and the order process can authorize the same pending order at the same time. Row locks do not work
 * on every database (HSQLDB has none), so only one caller may authorize: it holds a claim, a row with the key
 * {@code "authorize-" + paymentIntentId} in the unique index of the order placement lock. A caller that cannot take the
 * claim creates no entry: it waits a short time for the ACCEPTED entry of the holder and returns it, or throws so that it
 * tries again later. A claim older than the stale time of the lock service is removed, so a holder that stopped does not
 * block the order.
 */
public class DefaultStripeOrderAuthorizationService implements StripeOrderAuthorizationService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeOrderAuthorizationService.class);
	static final String CLAIM_PREFIX = "authorize-";
	private static final String ACCEPTED_AUTHORIZATIONS = "SELECT {e.pk} FROM {PaymentTransactionEntry AS e"
			+ " JOIN PaymentTransaction AS t ON {e.paymentTransaction} = {t.pk}}"
			+ " WHERE {t.order} = ?order AND {e.requestId} = ?requestId AND {e.type} = ?type"
			+ " AND {e.transactionStatus} = ?status";

	private PaymentService paymentService;
	private StripeAmountConverter stripeAmountConverter;
	private I18NService i18nService;
	private ModelService modelService;
	private FlexibleSearchService flexibleSearchService;
	private StripeOrderPlacementLockService stripeOrderPlacementLockService;
	private long claimWaitMillis = 5000L;
	private long claimPollMillis = 250L;

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
		final String claim = CLAIM_PREFIX + paymentIntentId;
		if (!stripeOrderPlacementLockService.tryAcquire(claim))
		{
			return awaitOtherAuthorization(order, paymentIntentId);
		}
		if (hasStoredAcceptedAuthorization(order, paymentIntentId))
		{
			LOG.info("Order {} already has an accepted authorization of payment intent {}: no new entry", order.getCode(),
					paymentIntentId);
			modelService.refresh(order);
			return true;
		}
		final boolean accepted;
		try
		{
			accepted = authorize(order, paymentIntentId);
		}
		catch (final RuntimeException e)
		{
			stripeOrderPlacementLockService.release(claim);
			throw e;
		}
		if (!accepted)
		{
			stripeOrderPlacementLockService.release(claim);
		}
		return accepted;
	}

	private boolean authorize(final OrderModel order, final String paymentIntentId)
	{
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

	/**
	 * Another caller holds the claim. Its ACCEPTED entry is read from the database until it is there or the wait ends.
	 * The order model is refreshed, so the caller sees the new transaction.
	 *
	 * @throws IllegalStateException when no ACCEPTED entry is there after the wait: the caller tries again later
	 */
	protected boolean awaitOtherAuthorization(final OrderModel order, final String paymentIntentId)
	{
		final long deadline = System.currentTimeMillis() + claimWaitMillis;
		while (true)
		{
			if (hasStoredAcceptedAuthorization(order, paymentIntentId))
			{
				LOG.info("Order {} was authorized by payment intent {} on another path: no new entry", order.getCode(),
						paymentIntentId);
				modelService.refresh(order);
				return true;
			}
			if (System.currentTimeMillis() >= deadline)
			{
				throw new IllegalStateException("Authorization of order " + order.getCode() + " by payment intent "
						+ paymentIntentId + " runs on another path");
			}
			pause();
		}
	}

	/**
	 * True when the database holds an ACCEPTED AUTHORIZATION entry of the PaymentIntent for the order. The order model in
	 * memory can be older than the database.
	 */
	protected boolean hasStoredAcceptedAuthorization(final OrderModel order, final String paymentIntentId)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(ACCEPTED_AUTHORIZATIONS, Map.of("order", order,
				"requestId", paymentIntentId, "type", PaymentTransactionType.AUTHORIZATION, "status",
				TransactionStatus.ACCEPTED.name()));
		return !flexibleSearchService.<PaymentTransactionEntryModel> search(query).getResult().isEmpty();
	}

	private void pause()
	{
		try
		{
			Thread.sleep(claimPollMillis);
		}
		catch (final InterruptedException e)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Wait for the authorization was interrupted", e);
		}
	}

	/**
	 * The transactions are removed, not marked: a cart transaction is not an audit record yet (SAP copies it to the
	 * order at placement), and SAP itself removes a cart transaction whose authorization was not accepted.
	 */
	@Override
	public void removeAuthorizations(final AbstractOrderModel cart, final String paymentIntentId)
	{
		final List<PaymentTransactionModel> transactions = cart.getPaymentTransactions() == null ? List.of()
				: List.copyOf(cart.getPaymentTransactions());
		final List<PaymentTransactionModel> stale = transactions.stream()
				.filter(transaction -> authorizes(transaction, paymentIntentId))
				.toList();
		if (stale.isEmpty())
		{
			return;
		}
		final List<Object> removed = new ArrayList<>();
		for (final PaymentTransactionModel transaction : stale)
		{
			if (transaction.getEntries() != null)
			{
				removed.addAll(transaction.getEntries());
			}
			removed.add(transaction);
		}
		modelService.removeAll(removed);
		final List<PaymentTransactionModel> kept = new ArrayList<>(transactions);
		kept.removeAll(stale);
		cart.setPaymentTransactions(kept);
		LOG.info("Removed {} payment transaction(s) of released payment intent {} from {}", stale.size(), paymentIntentId,
				cart.getCode());
	}

	private static boolean authorizes(final PaymentTransactionModel transaction, final String paymentIntentId)
	{
		return transaction.getEntries() != null && transaction.getEntries().stream()
				.filter(Objects::nonNull)
				.anyMatch(entry -> PaymentTransactionType.AUTHORIZATION.equals(entry.getType())
						&& paymentIntentId.equals(entry.getRequestId()));
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

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}

	public void setStripeOrderPlacementLockService(final StripeOrderPlacementLockService stripeOrderPlacementLockService)
	{
		this.stripeOrderPlacementLockService = stripeOrderPlacementLockService;
	}

	/**
	 * How long a caller without the claim waits for the authorization of the claim holder, in milliseconds.
	 */
	public void setClaimWaitMillis(final long claimWaitMillis)
	{
		this.claimWaitMillis = claimWaitMillis;
	}

	public void setClaimPollMillis(final long claimPollMillis)
	{
		this.claimPollMillis = claimPollMillis;
	}
}
