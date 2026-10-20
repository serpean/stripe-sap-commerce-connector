package com.stripe.core.order.impl;

import com.stripe.core.dao.StripePaymentIntentRecordDao;
import com.stripe.core.model.StripeOrderPlacementLockModel;
import com.stripe.core.order.StripeOrderPlacementLockService;
import de.hybris.platform.core.Registry;
import de.hybris.platform.servicelayer.exceptions.ModelRemovalException;
import de.hybris.platform.servicelayer.exceptions.ModelSavingException;
import de.hybris.platform.servicelayer.model.ModelService;
import de.hybris.platform.servicelayer.search.FlexibleSearchQuery;
import de.hybris.platform.servicelayer.search.FlexibleSearchService;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lock on the unique index of {@link StripeOrderPlacementLockModel#getPaymentIntentId()}. A lock older than
 * {@link #STALE_AFTER} whose PaymentIntent has no order is stale (its placement stopped before the order was made):
 * it is removed and the lock is taken again, once.
 */
public class DefaultStripeOrderPlacementLockService implements StripeOrderPlacementLockService
{
	static final Duration STALE_AFTER = Duration.ofMinutes(10);

	private static final Logger LOG = LoggerFactory.getLogger(DefaultStripeOrderPlacementLockService.class);
	private static final String BY_PAYMENT_INTENT_ID =
			"SELECT {pk} FROM {StripeOrderPlacementLock} WHERE {paymentIntentId} = ?paymentIntentId";

	private ModelService modelService;
	private FlexibleSearchService flexibleSearchService;
	private StripePaymentIntentRecordDao stripePaymentIntentRecordDao;

	@Override
	public boolean tryAcquire(final String paymentIntentId)
	{
		if (StringUtils.isBlank(paymentIntentId))
		{
			throw new IllegalArgumentException("paymentIntentId must not be blank");
		}
		if (insert(paymentIntentId))
		{
			return true;
		}
		return removeStaleLock(paymentIntentId) && insert(paymentIntentId);
	}

	@Override
	public void release(final String paymentIntentId)
	{
		find(paymentIntentId).ifPresent(lock -> {
			LOG.info("Releasing order placement lock of payment intent {}", paymentIntentId);
			modelService.remove(lock);
		});
	}

	protected boolean insert(final String paymentIntentId)
	{
		final StripeOrderPlacementLockModel lock = modelService.create(StripeOrderPlacementLockModel.class);
		lock.setPaymentIntentId(paymentIntentId);
		lock.setOwnerNode(String.valueOf(Registry.getClusterID()));
		try
		{
			modelService.save(lock);
			return true;
		}
		catch (final ModelSavingException e)
		{
			modelService.detach(lock);
			LOG.info("Order placement lock of payment intent {} is already taken", paymentIntentId);
			return false;
		}
	}

	protected boolean removeStaleLock(final String paymentIntentId)
	{
		final Optional<StripeOrderPlacementLockModel> existing = find(paymentIntentId);
		if (existing.isEmpty())
		{
			return true;
		}
		final StripeOrderPlacementLockModel lock = existing.get();
		if (!isStale(lock) || hasOrder(paymentIntentId))
		{
			return false;
		}
		LOG.warn("Removing stale order placement lock of payment intent {}, taken by node {} at {}", paymentIntentId,
				lock.getOwnerNode(), lock.getCreationtime());
		try
		{
			modelService.remove(lock);
		}
		catch (final ModelRemovalException e)
		{
			LOG.info("Stale order placement lock of payment intent {} was already removed", paymentIntentId);
		}
		return true;
	}

	private boolean isStale(final StripeOrderPlacementLockModel lock)
	{
		final Date created = lock.getCreationtime();
		return created != null && created.toInstant().isBefore(Instant.now().minus(STALE_AFTER));
	}

	private boolean hasOrder(final String paymentIntentId)
	{
		return stripePaymentIntentRecordDao.findByPaymentIntentId(paymentIntentId)
				.map(record -> record.getOrder() != null)
				.orElse(false);
	}

	private Optional<StripeOrderPlacementLockModel> find(final String paymentIntentId)
	{
		final FlexibleSearchQuery query = new FlexibleSearchQuery(BY_PAYMENT_INTENT_ID);
		query.addQueryParameter("paymentIntentId", paymentIntentId);
		return flexibleSearchService.<StripeOrderPlacementLockModel> search(query).getResult().stream().findFirst();
	}

	public void setModelService(final ModelService modelService)
	{
		this.modelService = modelService;
	}

	public void setFlexibleSearchService(final FlexibleSearchService flexibleSearchService)
	{
		this.flexibleSearchService = flexibleSearchService;
	}

	public void setStripePaymentIntentRecordDao(final StripePaymentIntentRecordDao stripePaymentIntentRecordDao)
	{
		this.stripePaymentIntentRecordDao = stripePaymentIntentRecordDao;
	}
}
