package com.stripe.core.payment.impl;

import com.stripe.core.amount.StripeAmountConverter;
import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.gateway.StripeGateway;
import com.stripe.core.enums.StripePaymentIntentState;
import com.stripe.core.model.StripePaymentIntentRecordModel;
import com.stripe.core.payment.StripePaymentIntentRecordService;
import com.stripe.core.payment.StripeTransactionLookup;
import com.stripe.core.status.StripePaymentIntentStatus;
import de.hybris.platform.core.model.order.OrderModel;
import de.hybris.platform.payment.model.PaymentTransactionModel;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Default {@link StripePaymentIntentRecordService}.
 */
public class DefaultStripePaymentIntentRecordService implements StripePaymentIntentRecordService
{
	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripePaymentIntentRecordService.class);

	private static final String LIVE_ORDER_BY_PAYMENT_INFO = "SELECT {o.pk} FROM {Order AS o"
			+ " JOIN StripePaymentInfo AS p ON {o.paymentInfo} = {p.pk}}"
			+ " WHERE {p.stripePaymentIntentId} = ?paymentIntentId AND {o.versionID} IS NULL";

	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;
	private StripeTransactionLookup stripeTransactionLookup;
	private StripeGateway stripeGateway;
	private StripeAmountConverter stripeAmountConverter;
	private FlexibleSearchService flexibleSearchService;
	private ModelService modelService;

	@Override
	public Optional<OrderModel> findLiveOrder(final String paymentIntentId)
	{
		final Optional<OrderModel> authorized = stripeTransactionLookup.findByPaymentIntentId(paymentIntentId)
				.map(PaymentTransactionModel::getOrder)
				.filter(OrderModel.class::isInstance)
				.map(OrderModel.class::cast);
		if (authorized.isPresent())
		{
			return authorized;
		}
		final FlexibleSearchQuery query = new FlexibleSearchQuery(LIVE_ORDER_BY_PAYMENT_INFO);
		query.addQueryParameter("paymentIntentId", paymentIntentId);
		final List<OrderModel> orders = flexibleSearchService.<OrderModel> search(query).getResult();
		if (orders.size() > 1)
		{
			LOG.error("Payment intent {} belongs to {} live orders", paymentIntentId, orders.size());
		}
		return orders.stream().findFirst();
	}

	@Override
	public void linkOrder(final String paymentIntentId, final OrderModel order)
	{
		final StripePaymentIntentRecordModel record = stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntentId)
				.orElseGet(() -> newRecord(paymentIntentId, order));
		record.setOrder(order);
		if (record.getState() == null || record.getState() == StripePaymentIntentState.CREATED)
		{
			record.setState(currentState(paymentIntentId, record.getState()));
		}
		modelService.save(record);
	}

	protected StripePaymentIntentRecordModel newRecord(final String paymentIntentId, final OrderModel order)
	{
		LOG.warn("Payment intent {} of order {} has no record: creating it", paymentIntentId, order.getCode());
		final StripePaymentIntentRecordModel record = modelService.create(StripePaymentIntentRecordModel.class);
		record.setPaymentIntentId(paymentIntentId);
		record.setCartCode(order.getCode());
		record.setBaseSiteUid(order.getSite() == null ? "" : order.getSite().getUid());
		record.setUserUid(order.getUser() == null ? "" : order.getUser().getUid());
		record.setCurrencyIso(order.getCurrency().getIsocode());
		record.setAmount(stripeAmountConverter.chargeAmountMinor(order));
		record.setState(StripePaymentIntentState.CREATED);
		return record;
	}

	private StripePaymentIntentState currentState(final String paymentIntentId, final StripePaymentIntentState known)
	{
		try
		{
			switch (StripePaymentIntentStatus.of(stripeGateway.retrievePaymentIntent(paymentIntentId)))
			{
				case AUTHORIZED:
					return StripePaymentIntentState.AUTHORIZED;
				case CAPTURED:
					return StripePaymentIntentState.CAPTURED;
				case PROCESSING:
					return StripePaymentIntentState.PROCESSING;
				case AWAITING_FUNDS:
					return StripePaymentIntentState.AWAITING_FUNDS;
				case FAILED:
					return StripePaymentIntentState.FAILED;
				case CANCELED:
					return StripePaymentIntentState.CANCELED;
				default:
					return StripePaymentIntentState.CREATED;
			}
		}
		catch (final RuntimeException e)
		{
			LOG.warn("Could not read the status of payment intent {}; its record keeps state {}", paymentIntentId, known, e);
			return known;
		}
	}

	public void setStripePaymentIntentRecordDao(final StripePaymentIntentRecordDao stripePaymentIntentRecordDao)
	{
		this.stripePaymentIntentRecordDao = stripePaymentIntentRecordDao;
	}

	public void setStripeTransactionLookup(final StripeTransactionLookup stripeTransactionLookup)
	{
		this.stripeTransactionLookup = stripeTransactionLookup;
	}

	public void setStripeGateway(final StripeGateway stripeGateway)
	{
		this.stripeGateway = stripeGateway;
	}

	public void setStripeAmountConverter(final StripeAmountConverter stripeAmountConverter)
	{
		this.stripeAmountConverter = stripeAmountConverter;
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}
}
