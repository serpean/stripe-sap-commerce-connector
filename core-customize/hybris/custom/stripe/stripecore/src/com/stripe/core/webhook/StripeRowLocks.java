package com.stripe.core.webhook;

import de.hybris.platform.core.model.ItemModel;
import de.hybris.platform.servicelayer.model.ModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locks the database row of an item for the current transaction (SELECT ... FOR UPDATE) and reloads the item. HSQLDB,
 * the development database, has no row locks: there the item is only reloaded and a warning is logged.
 */
public final class StripeRowLocks
{
	private static final Logger LOG = LoggerFactory.getLogger(StripeRowLocks.class);

	private StripeRowLocks()
	{
	}

	public static void lockAndRefresh(final ModelService modelService, final ItemModel item)
	{
		try
		{
			modelService.lock(item.getPk());
		}
		catch (final UnsupportedOperationException e)
		{
			LOG.warn("The database has no row locks: {} is not locked ({})", item.getPk(), e.getMessage());
		}
		modelService.refresh(item);
	}
}
